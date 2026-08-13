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
import com.quantumlink.im.common.util.JsonUtil;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * QuantumLink Netty 压测客户端(替代 Node 多进程方案,单进程多线程吃多核)。
 *
 * <p>用法: java -jar im-loadtest.jar [连接数] [每连接每秒消息数] [时长秒] [host] [端口列表] [quiet]
 * <pre>
 *   默认:     100  5  20  127.0.0.1  19001,19002  false
 *   示例:     java -jar im-loadtest.jar 1000 5 30 8.141.86.246 19001,19002
 * </pre>
 *
 * <p>流程:并发注册登录 N 用户(HTTP)→ Netty 并发建连 + 握手 → 每连接定时灌消息 →
 * 按 ACK-STORE 统计端到端延迟 → 汇总 P50/P90/P99/吞吐/送达率。
 *
 * <p>为什么用 Netty:Node 单进程单线程,压测客户端自身成为瓶颈(压测报告实测单进程
 * 30 连接延迟差 10 倍);Netty 客户端单进程多线程 + 复用 im-common 协议编解码,协议同构。
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

    // ---- 全局统计 ----
    static final LongAdder handshakeOk = new LongAdder();
    static final LongAdder handshakeFail = new LongAdder();
    static final LongAdder sent = new LongAdder();
    static final LongAdder acked = new LongAdder();
    static final LongAdder dupAck = new LongAdder();
    static final ConcurrentLinkedQueue<Long> latenciesMs = new ConcurrentLinkedQueue<>();

    /** 压测用户(注册登录产物) */
    static class TestUser {
        String username, password = "pass123", token, deviceId, userId;
    }

    /** 登录响应解析(与 chat AuthDtos.LoginResponse 对齐) */
    static class LoginResp {
        public boolean success;
        public String token, deviceId, userId;
    }

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        apiBase = "http://" + host + ":8081";

        if (!quiet) {
            log.info("QuantumLink Netty 压测客户端: connections={} msgPerSec={} duration={}s host={} ports={}",
                    connections, msgPerSec, durationSec, host, Arrays.toString(ports));
        }

        // 1. 并发注册登录 N 用户
        TestUser[] users = new TestUser[connections];
        ExecutorService regPool = Executors.newFixedThreadPool(Math.min(16, Math.max(4, connections)));
        CountDownLatch regDone = new CountDownLatch(connections);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < connections; i++) {
            final int idx = i;
            regPool.submit(() -> {
                try { users[idx] = registerAndLogin(idx); }
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

        // 2. Netty 并发建连 + 握手
        EventLoopGroup group = new NioEventLoopGroup(Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors())));
        List<TestClient> clients = new ArrayList<>();
        CountDownLatch allConnected = new CountDownLatch(connections);
        for (int i = 0; i < connections; i++) {
            TestUser u = users[i];
            if (u == null) { allConnected.countDown(); continue; }
            TestClient tc = new TestClient(group, u, ports[i % ports.length],
                    users[(i + 1) % connections] == null ? null : users[(i + 1) % connections].userId, allConnected);
            clients.add(tc);
        }
        allConnected.await(20, TimeUnit.SECONDS);
        if (!quiet) log.info("握手完成: 成功={} 失败={}", handshakeOk.sum(), handshakeFail.sum());

        // 3. 全部握手完成后,统一开始灌消息(durationSec)
        for (TestClient tc : clients) tc.startSending();

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

        // 4. 关闭 + 汇总
        for (TestClient tc : clients) tc.close();
        group.shutdownGracefully().sync();

        long sentN = sent.sum(), ackedN = acked.sum();
        long[] lat = latenciesMs.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(lat);
        System.out.println("========== QuantumLink 压测结果 ==========");
        System.out.println("连接 : 总=" + connections + " 握手成功=" + handshakeOk.sum() + " 失败=" + handshakeFail.sum());
        System.out.println("消息 : 发送=" + sentN + " ACK-STORE=" + ackedN
                + " 未确认=" + (sentN - ackedN) + " 重复ACK=" + dupAck.sum()
                + " 送达率=" + (sentN > 0 ? String.format("%.1f", 100.0 * ackedN / sentN) : "0") + "%");
        System.out.println("吞吐 : " + String.format("%.1f", ackedN / (double) durationSec) + " 条/秒(MSG 单向;含 DELIVER_ACK 双倍)");
        System.out.println("延迟(发→ACK-STORE): P50=" + pct(lat, 50) + "ms P90=" + pct(lat, 90)
                + "ms P99=" + pct(lat, 99) + "ms 样本=" + lat.length);
        System.out.println("========================================");
        System.exit(0);
    }

    // ==================== HTTP 注册登录 ====================

    static TestUser registerAndLogin(int idx) throws Exception {
        String uname = "lt" + idx + "_" + (System.currentTimeMillis() % 1_000_000);
        TestUser u = new TestUser();
        u.username = uname;
        post(apiBase + "/api/auth/register",
                "{\"username\":\"" + uname + "\",\"password\":\"pass123\"}");
        String login = post(apiBase + "/api/auth/login",
                "{\"username\":\"" + uname + "\",\"password\":\"pass123\",\"deviceType\":\"loadtest\"}");
        LoginResp resp = JsonUtil.fromJson(login, LoginResp.class);
        if (resp == null || !resp.success) {
            throw new IllegalStateException("login failed: " + login);
        }
        u.token = resp.token;
        u.deviceId = resp.deviceId;
        u.userId = resp.userId;
        return u;
    }

    static String post(String url, String json) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    // ==================== 单连接压测客户端 ====================

    static class TestClient {
        final TestUser user;
        final String receiverUserId;
        final ChannelFuture future;
        volatile PerHandler handler;   // initChannel 时赋值(非 final,匿名类里可写)

        TestClient(EventLoopGroup group, TestUser u, int port, String receiver, CountDownLatch latch) {
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
            if (future.channel() != null) future.channel().close();
        }
    }

    /** 单连接的帧处理:握手 → 心跳 → 定时发消息 → ACK 延迟统计 */
    static class PerHandler extends SimpleChannelInboundHandler<ImFrame> {
        static final long HEARTBEAT_MS = 10_000;
        static final long CONN_START = System.nanoTime();

        final TestUser user;
        final String receiverUserId;
        final CountDownLatch latch;
        final ConcurrentHashMap<String, Long> pending = new ConcurrentHashMap<>(); // clientMsgId -> 发送时刻(ns)
        Channel channel;
        int msgSeq = 0;
        volatile boolean connected = false;

        PerHandler(TestUser u, String receiver, CountDownLatch latch) {
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
                case PONG -> { /* 心跳应答,无需处理 */ }
                default -> { /* 压测只关心回执 */ }
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
                // sender 由主流程在所有连接握手完成后统一启动(startSending)
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

        /** 心跳:每 10s PING(与服务端一致) */
        private void startHeartbeat() {
            channel.eventLoop().scheduleAtFixedRate(() -> {
                if (channel.isActive()) {
                    channel.writeAndFlush(ProtocolUtil.buildFrame(FrameType.PING, new byte[0]));
                }
            }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        }

        /** 定时发消息:每 sendInterval 一条,发给配对用户;clientMsgId=UUID 幂等键 */
        private void startSender() {
            long intervalMs = Math.max(1, 1000 / Math.max(1, msgPerSec));
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

    /** 每 5s 实时进度:PROGRESS|elapsed|sent|acked|latencyP50|latencyP90|latencyP99(压测控制台解析,不受 quiet 控制) */
    static void reportProgress(int elapsedSec) {
        long[] lat = latenciesMs.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(lat);
        System.out.printf("PROGRESS|%d|%d|%d|%.0f|%.0f|%.0f%n",
                elapsedSec, sent.sum(), acked.sum(), pct(lat, 50), pct(lat, 90), pct(lat, 99));
    }

    static double pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    static void parseArgs(String[] args) {
        if (args.length > 0) connections = Integer.parseInt(args[0]);
        if (args.length > 1) msgPerSec = Integer.parseInt(args[1]);
        if (args.length > 2) durationSec = Integer.parseInt(args[2]);
        if (args.length > 3) host = args[3];
        if (args.length > 4) ports = Arrays.stream(args[4].split(",")).mapToInt(Integer::parseInt).toArray();
        if (args.length > 5) quiet = Boolean.parseBoolean(args[5]);
    }
}
