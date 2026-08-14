package com.quantumlink.im.loadtest;

import com.quantumlink.im.common.protocol.AckPayload;
import com.quantumlink.im.common.protocol.AckType;
import com.quantumlink.im.common.protocol.FrameType;
import com.quantumlink.im.common.protocol.HandshakeAckPayload;
import com.quantumlink.im.common.protocol.HandshakePayload;
import com.quantumlink.im.common.protocol.ImFrame;
import com.quantumlink.im.common.protocol.ImFrameDecoder;
import com.quantumlink.im.common.protocol.ImFrameEncoder;
import com.quantumlink.im.common.protocol.MessagePayload;
import com.quantumlink.im.common.util.ProtocolUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * QuantumLink Netty 压测客户端(替代 Node 多进程方案,单进程多线程吃多核)。
 *
 * <p>用法:
 * <pre>
 *   java -jar im-loadtest.jar [连接数] [每连接每秒消息数] [时长秒] [host] [端口列表] [quiet]
 *     [--ramp N]         每秒建 N 连接(默认 0=全量并发)
 *     [--heartbeat N]    心跳间隔秒(默认 10,必须 &lt; 服务端 IdleState/TTL 的 30s)
 *     [--deliver-ack]    收到消息后回 DELIVER_ACK(默认不回,测信令洪峰用)
 *   默认:  100  5  20  127.0.0.1  19001,19002  false
 *   示例:  java -jar im-loadtest.jar 1000 5 30 8.141.86.246 19001,19002
 *   空连接: java -jar im-loadtest.jar 10000 0 300 127.0.0.1 19001,19002 --ramp 500 --heartbeat 15
 * </pre>
 *
 * <p>流程:并发注册登录 N 用户(HTTP)→ Netty 建连 + 握手(支持 ramp) → 每连接定时灌消息 →
 * 按 ACK-STORE 统计"发→落库"延迟;接收方统计"实收/去重/端到端延迟/乱序"(同 JVM 时钟,
 * clientTime 由 chat 原样下发) → 汇总 P50/P90/P99/吞吐/送达率/不重率。
 *
 * <p>指标口径(压测报告4 六指标):
 * <ul>
 *   <li>ack 延迟 = 发→ACK-STORE(落库回执,不含推送段)</li>
 *   <li>端到端延迟 = now - clientTime(含全部链路,到接收方渲染)</li>
 *   <li>实收口径送达率 = 唯一实收/发送(消息不重率 = 1 - 重复实收/实收)</li>
 * </ul>
 */
public class LoadTestClient {
    private static final Logger log = LoggerFactory.getLogger(LoadTestClient.class);

    // ---- 参数 ----
    static int connections = 100;
    static int msgPerSec = 5;
    static int durationSec = 20;
    static String host = "127.0.0.1";
    static int[] ports = {19001, 19002};
    static String apiBase = "http://127.0.0.1:8081";
    static boolean quiet = false;
    static int rampPerSec = 0;       // --ramp:每秒建连数,0 = 全量并发
    static int heartbeatSec = 10;    // --heartbeat:心跳间隔(秒),必须 < 30(服务端 IdleState/TTL)
    static boolean deliverAck = false; // --deliver-ack:收消息后回 DELIVER_ACK

    // ---- 全局统计 ----
    static final LongAdder handshakeOk = new LongAdder();
    static final LongAdder handshakeFail = new LongAdder();
    static final LongAdder sent = new LongAdder();
    static final LongAdder acked = new LongAdder();
    static final LongAdder dupAck = new LongAdder();
    static final ConcurrentLinkedQueue<Long> latenciesMs = new ConcurrentLinkedQueue<>(); // 发→ACK-STORE
    static final LongAdder recv = new LongAdder();      // 收到 MSG 帧数(含重复)
    static final LongAdder dupRecv = new LongAdder();   // 重复实收(serverMsgId 已见)
    static final LongAdder ooo = new LongAdder();       // 乱序(seq <= 该会话已见最大 seq)
    static final LongAdder gap = new LongAdder();       // 缺号(seq > 已见最大 seq + 1,后续可能补到)
    static final ConcurrentLinkedQueue<Long> e2eLatenciesMs = new ConcurrentLinkedQueue<>(); // now-clientTime
    static final Set<String> seenMsgIds = ConcurrentHashMap.newKeySet(); // 全局去重
    static final LongAdder closedConns = new LongAdder(); // 压测期间意外掉线(主动 close 不计)

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        apiBase = "http://" + host + ":8081";

        if (!quiet) {
            log.info("QuantumLink Netty 压测客户端: connections={} msgPerSec={} duration={}s host={} ports={} ramp={}/s heartbeat={}s deliverAck={}",
                    connections, msgPerSec, durationSec, host, Arrays.toString(ports),
                    rampPerSec, heartbeatSec, deliverAck);
        }

        // 1. 并发注册登录 N 用户
        LoadTestSupport.TestUser[] users = new LoadTestSupport.TestUser[connections];
        ExecutorService regPool = Executors.newFixedThreadPool(Math.min(16, Math.max(4, connections)));
        CountDownLatch regDone = new CountDownLatch(connections);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < connections; i++) {
            final int idx = i;
            regPool.submit(() -> {
                try { users[idx] = LoadTestSupport.registerAndLogin("lt", idx, apiBase); }
                catch (Exception e) { if (!quiet) log.warn("注册登录失败[{}]: {}", idx, e.getMessage()); }
                finally { regDone.countDown(); }
            });
        }
        regDone.await();
        regPool.shutdown();
        if (!quiet) {
            long ok = Arrays.stream(users).filter(Objects::nonNull).count();
            log.info("注册登录完成: {}/{} 成功, 耗时 {}ms", ok, connections, System.currentTimeMillis() - t0);
        }

        // 2. Netty 建连 + 握手(ramp 模式按每秒 N 个分批,其余全量并发)
        EventLoopGroup group = new NioEventLoopGroup(Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors())));
        List<TestClient> clients = new ArrayList<>();
        CountDownLatch allConnected = new CountDownLatch(connections);
        long awaitTimeoutSec = rampPerSec > 0 ? connections / (long) Math.max(1, rampPerSec) + 60 : 20;
        if (rampPerSec > 0) {
            long done = 0;
            while (done < connections) {
                int batch = (int) Math.min(rampPerSec, connections - done);
                for (int k = 0; k < batch; k++) connectOne(group, users, clients, allConnected, (int) (done + k));
                done += batch;
                if (done < connections) Thread.sleep(1000); // 每秒一批
            }
        } else {
            for (int i = 0; i < connections; i++) connectOne(group, users, clients, allConnected, i);
        }
        allConnected.await(awaitTimeoutSec, TimeUnit.SECONDS);
        if (!quiet) log.info("握手完成: 成功={} 失败={} (await {}s)", handshakeOk.sum(), handshakeFail.sum(), awaitTimeoutSec);

        // 3. 全部握手完成后统一开始灌消息(空连接模式跳过)
        if (msgPerSec > 0) {
            for (TestClient tc : clients) tc.startSending();
        }

        long t1 = System.currentTimeMillis();
        long lastReport = -1;
        while (System.currentTimeMillis() - t1 < durationSec * 1000L) {
            Thread.sleep(1000);
            long elapsed = (System.currentTimeMillis() - t1) / 1000;
            if (elapsed - lastReport >= 5) {   // 每 5s 输出实时进度(PROGRESS 行,压测控制台解析)
                lastReport = elapsed;
                reportProgress((int) elapsed);
            }
        }
        reportProgress((int) durationSec);     // 结束前补最后一次

        // 4. 关闭 + 汇总(先标记主动关闭,channelInactive 不再计掉线)
        for (TestClient tc : clients) tc.close();
        group.shutdownGracefully().sync();

        long sentN = sent.sum(), ackedN = acked.sum();
        long recvN = recv.sum(), dupN = dupRecv.sum(), uniqN = recvN - dupN;
        long[] lat = latenciesMs.stream().mapToLong(Long::longValue).toArray();
        long[] e2e = e2eLatenciesMs.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(lat); Arrays.sort(e2e);
        System.out.println("========== QuantumLink 压测结果 ==========");
        System.out.println("连接 : 总=" + connections + " 握手成功=" + handshakeOk.sum()
                + " 失败=" + handshakeFail.sum() + " 压测期掉线=" + closedConns.sum());
        if (msgPerSec > 0) {
            System.out.println("消息 : 发送=" + sentN + " ACK-STORE=" + ackedN
                    + " 未确认=" + (sentN - ackedN) + " 重复ACK=" + dupAck.sum()
                    + " 送达率(ACK口径)=" + (sentN > 0 ? String.format("%.1f", 100.0 * ackedN / sentN) : "0") + "%");
            System.out.println("接收 : 实收=" + recvN + " 唯一=" + uniqN + " 重复=" + dupN
                    + " 不重率=" + (recvN > 0 ? String.format("%.4f", 1.0 - (double) dupN / recvN) : "-")
                    + " 送达率(实收口径)=" + (sentN > 0 ? String.format("%.1f", 100.0 * uniqN / sentN) : "0") + "%");
            System.out.println("顺序 : 乱序=" + ooo.sum() + " 缺号=" + gap.sum());
            System.out.println("吞吐 : " + String.format("%.1f", ackedN / (double) durationSec) + " 条/秒(MSG 单向)");
            System.out.println("延迟(发→ACK-STORE): P50=" + LoadTestSupport.pct(lat, 50) + "ms P90="
                    + LoadTestSupport.pct(lat, 90) + "ms P99=" + LoadTestSupport.pct(lat, 99) + "ms 样本=" + lat.length);
            System.out.println("延迟(端到端,发→接收方): P50=" + LoadTestSupport.pct(e2e, 50) + "ms P90="
                    + LoadTestSupport.pct(e2e, 90) + "ms P99=" + LoadTestSupport.pct(e2e, 99) + "ms 样本=" + e2e.length);
        }
        System.out.println("========================================");
        System.exit(0);
    }

    private static void connectOne(EventLoopGroup group, LoadTestSupport.TestUser[] users,
                                   List<TestClient> clients, CountDownLatch latch, int i) {
        LoadTestSupport.TestUser u = users[i];
        if (u == null) { latch.countDown(); return; }
        // 空连接模式(msgPerSec=0)不配对:receiver 为 null,sendMessage 守卫跳过
        String receiver = msgPerSec > 0 && users[(i + 1) % connections] != null
                ? users[(i + 1) % connections].userId : null;
        TestClient tc = new TestClient(group, u, ports[i % ports.length], receiver, latch);
        clients.add(tc);
    }

    // ==================== 单连接压测客户端 ====================

    static class TestClient {
        final LoadTestSupport.TestUser user;
        final String receiverUserId;
        final ChannelFuture future;
        volatile PerHandler handler;   // initChannel 时赋值(非 final,匿名类里可写)

        TestClient(EventLoopGroup group, LoadTestSupport.TestUser u, int port, String receiver, CountDownLatch latch) {
            this.user = u;
            this.receiverUserId = receiver;
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            PerHandler h = new PerHandler(u, receiver, latch);
                            handler = h;
                            // 与服务端 pipeline 对称:decoder(拆包)+ encoder(出站编码)
                            ch.pipeline().addLast(new ImFrameDecoder(), new ImFrameEncoder(), h);
                        }
                    });
            this.future = b.connect(host, port);
        }

        /** 全部连接握手完成后统一启动发消息(避免接收方尚未注册就开灌 → target offline) */
        void startSending() {
            PerHandler h = handler;
            if (h != null && h.channel != null) {
                h.channel.eventLoop().execute(h::startSender);
            }
        }

        void close() {
            PerHandler h = handler;
            if (h != null) h.statsClosed = true;   // 主动关闭,channelInactive 不计掉线
            if (future.channel() != null) future.channel().close();
        }
    }

    /** 单连接的帧处理:握手 → 心跳 → 定时发消息 → ACK/接收统计 */
    static class PerHandler extends SimpleChannelInboundHandler<ImFrame> {
        static final long CONN_START = System.nanoTime();

        final LoadTestSupport.TestUser user;
        final String receiverUserId;
        final CountDownLatch latch;
        final ConcurrentHashMap<String, Long> pending = new ConcurrentHashMap<>(); // clientMsgId -> 发送时刻(ns)
        final ConcurrentHashMap<String, Long> convLastSeq = new ConcurrentHashMap<>(); // 会话 -> 已见最大 seq
        Channel channel;
        int msgSeq = 0;
        volatile boolean connected = false;
        volatile boolean statsClosed = false;

        PerHandler(LoadTestSupport.TestUser u, String receiver, CountDownLatch latch) {
            this.user = u;
            this.receiverUserId = receiver;
            this.latch = latch;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            this.channel = ctx.channel();
            // 首帧握手:token + deviceId
            HandshakePayload hs = new HandshakePayload();
            hs.setToken(user.token);
            hs.setDeviceId(user.deviceId);
            ctx.writeAndFlush(ProtocolUtil.buildFrame(FrameType.HANDSHAKE, hs));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ImFrame frame) {
            switch (frame.getType()) {
                case HANDSHAKE_ACK -> onHandshakeAck(frame);
                case MSG_ACK -> onAck(frame);
                case MSG -> onMsg(frame);
                case PONG -> { /* 心跳应答,无需处理 */ }
                default -> { /* 压测只关心回执与消息 */ }
            }
        }

        private void onHandshakeAck(ImFrame frame) {
            HandshakeAckPayload ack = ProtocolUtil.parseBody(frame, HandshakeAckPayload.class);
            if (ack != null && ack.isSuccess()) {
                connected = true;
                handshakeOk.increment();
                long elapsed = (System.nanoTime() - CONN_START) / 1_000_000;
                if (!quiet) log.info("握手成功: user={} 耗时={}ms", user.username, elapsed);
                startHeartbeat();
            } else {
                handshakeFail.increment();
            }
            latch.countDown();
        }

        private void onAck(ImFrame frame) {
            AckPayload ack = ProtocolUtil.parseBody(frame, AckPayload.class);
            if (ack == null || ack.getAckType() == null || ack.getAckType() != AckType.STORE) {
                return; // DELIVER / READ 不参与延迟统计
            }
            Long t = pending.remove(ack.getClientMsgId());
            if (t != null) {
                latenciesMs.add((System.nanoTime() - t) / 1_000_000);
                acked.increment();
            } else {
                dupAck.increment(); // 重复 ACK(重传/乱序)
            }
        }

        /** 接收方统计:实收/去重/端到端延迟/seq 顺序 + 可选 DELIVER_ACK 回执 */
        private void onMsg(ImFrame frame) {
            MessagePayload m = ProtocolUtil.parseBody(frame, MessagePayload.class);
            if (m == null || m.getServerMsgId() == null) return;
            recv.increment();
            if (!seenMsgIds.add(m.getServerMsgId())) {
                dupRecv.increment();
            }
            if (m.getClientTime() != null) {
                e2eLatenciesMs.add(System.currentTimeMillis() - m.getClientTime()); // 同 JVM 时钟可比
            }
            if (m.getSeq() != null && m.getConversationId() != null) {
                convLastSeq.compute(m.getConversationId(), (k, last) -> {
                    if (last == null) return m.getSeq();
                    if (m.getSeq() <= last) ooo.increment();        // 旧消息迟到/重复推送
                    else if (m.getSeq() > last + 1) gap.increment(); // 中间缺号(窗口内可能补到)
                    return m.getSeq();
                });
            }
            if (deliverAck) {
                AckPayload ack = new AckPayload();
                ack.setAckType(AckType.DELIVER);
                ack.setClientMsgId(m.getClientMsgId());
                ack.setServerMsgId(m.getServerMsgId());
                ack.setConversationId(m.getConversationId());
                channel.writeAndFlush(ProtocolUtil.buildFrame(FrameType.DELIVER_ACK, ack));
            }
        }

        /** 心跳:每 heartbeatSec 秒 PING(初始延迟加 0-5s 随机 jitter,防 3 万连接同拍打雷群) */
        private void startHeartbeat() {
            long intervalMs = heartbeatSec * 1000L;
            long jitter = ThreadLocalRandom.current().nextLong(0, 5000);
            channel.eventLoop().scheduleAtFixedRate(() -> {
                if (channel.isActive()) {
                    channel.writeAndFlush(ProtocolUtil.buildFrame(FrameType.PING, new byte[0]));
                }
            }, intervalMs + jitter, intervalMs, TimeUnit.MILLISECONDS);
        }

        /** 定时发消息:每 sendInterval 一条,发给配对用户;clientMsgId=UUID 幂等键。速率 0 = 纯连接模式(只建连+心跳) */
        private void startSender() {
            if (msgPerSec <= 0) return;   // 0 速率:只建连 + 心跳,测接入层连接承载(chat 不受业务压力)
            long intervalMs = Math.max(1, 1000 / msgPerSec);
            channel.eventLoop().scheduleAtFixedRate(this::sendMessage, 500, intervalMs, TimeUnit.MILLISECONDS);
        }

        private void sendMessage() {
            if (!channel.isActive() || receiverUserId == null) return;
            MessagePayload m = new MessagePayload();
            m.setClientMsgId(UUID.randomUUID().toString());
            m.setReceiverId(receiverUserId);
            m.setMsgType("TEXT");
            m.setContent("lt-" + user.username + "-" + (msgSeq++));
            m.setClientTime(System.currentTimeMillis());
            pending.put(m.getClientMsgId(), System.nanoTime());
            sent.increment();
            channel.writeAndFlush(ProtocolUtil.buildFrame(FrameType.MSG, m));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!statsClosed && connected) closedConns.increment(); // 压测期间意外掉线
            if (!connected) handshakeFail.increment();
            latch.countDown();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!connected) handshakeFail.increment();
            latch.countDown();
            if (!quiet) log.warn("连接异常: {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
            ctx.close();
        }
    }

    // ==================== 工具 ====================

    /** 每 5s 实时进度(PROGRESS 行,压测控制台解析;空连接模式输出 IDLE 行) */
    static void reportProgress(int elapsedSec) {
        long[] lat = latenciesMs.stream().mapToLong(Long::longValue).toArray();
        long[] e2e = e2eLatenciesMs.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(lat); Arrays.sort(e2e);
        long recvN = recv.sum(), dupN = dupRecv.sum();
        if (msgPerSec == 0) {
            System.out.printf("IDLE|%d|%d|%d|%d%n", elapsedSec, handshakeOk.sum(), handshakeFail.sum(), closedConns.sum());
            return;
        }
        System.out.printf("PROGRESS|%d|%d|%d|%.0f|%.0f|%.0f|%d|%d|%d|%.0f|%.0f|%.0f%n",
                elapsedSec, sent.sum(), acked.sum(),
                LoadTestSupport.pct(lat, 50), LoadTestSupport.pct(lat, 90), LoadTestSupport.pct(lat, 99),
                recvN, recvN - dupN, dupN,
                LoadTestSupport.pct(e2e, 50), LoadTestSupport.pct(e2e, 90), LoadTestSupport.pct(e2e, 99));
    }

    /** 位置参数 + flag 混用解析(--ramp/--heartbeat/--deliver-ack 可出现在任意位置) */
    static void parseArgs(String[] args) {
        int pos = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--ramp" -> rampPerSec = Integer.parseInt(args[++i]);
                case "--heartbeat" -> heartbeatSec = Integer.parseInt(args[++i]);
                case "--deliver-ack" -> deliverAck = true;
                default -> {
                    switch (pos++) {
                        case 0 -> connections = Integer.parseInt(a);
                        case 1 -> msgPerSec = Integer.parseInt(a);
                        case 2 -> durationSec = Integer.parseInt(a);
                        case 3 -> host = a;
                        case 4 -> ports = Arrays.stream(a.split(",")).mapToInt(Integer::parseInt).toArray();
                        case 5 -> quiet = Boolean.parseBoolean(a);
                        default -> throw new IllegalArgumentException("多余参数: " + a + "(flag: --ramp N / --heartbeat N / --deliver-ack)");
                    }
                }
            }
        }
    }
}
