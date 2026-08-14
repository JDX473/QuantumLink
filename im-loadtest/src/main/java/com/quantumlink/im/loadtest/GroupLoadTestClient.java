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
import com.quantumlink.im.common.protocol.ReadReportPayload;
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
 * QuantumLink 群扇出压测客户端(读扩散 + 信封 targets 按节点聚合)。
 *
 * <p>用法:
 * <pre>
 *   java -cp im-loadtest.jar com.quantumlink.im.loadtest.GroupLoadTestClient \
 *        [群大小] [群数] [每群速率msg/s] [时长秒] [host] [端口列表] [每群发送者数] [quiet]
 *     [--read-ack]  成员收到群消息后回 READ_ACK(测群已读链路负载)
 *   示例: java -cp im-loadtest.jar com.quantumlink.im.loadtest.GroupLoadTestClient 100 1 5 60 127.0.0.1 19001,19002 1
 * </pre>
 *
 * <p>流程:注册 群数×群大小 用户(ltg 前缀)→ 每群 owner HTTP 建群(自动加全部成员)→
 * 成员建连握手 → 每群前 K 个成员灌群消息(conversationId=receiverId=groupId,读扩散) →
 * 汇总。
 *
 * <p>指标口径:
 * <ul>
 *   <li><b>群 ack 含完整扇出段</b>(落库→成员查询→逐成员 nodeOf→扇出 MQ→才回 STORE ack),
 *       所以 ack P99 本身就是"单条群消息端到端扇出成本"的度量</li>
 *   <li>扇出倍数 = 实收/发送,理论 = 群大小-1(发送者不在 targets,排除自己)</li>
 *   <li>送达率 = 实收/(发送×(S-1));不重率 = 1 - 重复/实收</li>
 * </ul>
 */
public class GroupLoadTestClient {
    private static final Logger log = LoggerFactory.getLogger(GroupLoadTestClient.class);

    // ---- 参数 ----
    static int groupSize = 100;
    static int groupCount = 1;
    static int msgPerSec = 5;          // 每群速率
    static int durationSec = 60;
    static String host = "127.0.0.1";
    static int[] ports = {19001, 19002};
    static int sendersPerGroup = 1;
    static boolean quiet = false;
    static boolean readAck = false;    // --read-ack:收消息后回 READ_ACK

    static String apiBase = "http://127.0.0.1:8081";

    // ---- 全局统计 ----
    static final LongAdder handshakeOk = new LongAdder();
    static final LongAdder handshakeFail = new LongAdder();
    static final LongAdder closedConns = new LongAdder();
    static final List<GroupStats> allStats = new ArrayList<>();

    /** 每群独立统计(去重/顺序/延迟都按群隔离,群间不混) */
    static class GroupStats {
        final String groupId;
        final int size;                              // 群人数
        final LongAdder sent = new LongAdder();
        final LongAdder acked = new LongAdder();
        final LongAdder dupAck = new LongAdder();
        final LongAdder recv = new LongAdder();
        final LongAdder dupRecv = new LongAdder();
        final LongAdder ooo = new LongAdder();
        final LongAdder gap = new LongAdder();
        final ConcurrentLinkedQueue<Long> ackLats = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> e2eLats = new ConcurrentLinkedQueue<>();
        final ConcurrentHashMap<String, Long> pending = new ConcurrentHashMap<>(); // 发送者:clientMsgId→ns
        // 注意:去重/顺序检测不能放这里(每群共享)——同批消息各成员都会收到,共享 Set
        // 会让第二个成员全判"重复/乱序"。去重按连接(PerHandler)各自维护。

        GroupStats(String groupId, int size) {
            this.groupId = groupId;
            this.size = size;
        }

        long uniqRecv() { return recv.sum() - dupRecv.sum(); }
    }

    /** 建群响应解析(与 GroupController.createGroup 对齐) */
    static class CreateGroupResp {
        public boolean success;
        public String groupId;
    }

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        apiBase = "http://" + host + ":8081";
        sendersPerGroup = Math.min(sendersPerGroup, groupSize);
        int total = groupSize * groupCount;
        if (!quiet) {
            log.info("群扇出压测: 群大小={} 群数={} 每群速率={}/s 时长={}s host={} ports={} 发送者/群={} readAck={}",
                    groupSize, groupCount, msgPerSec, durationSec, host, Arrays.toString(ports), sendersPerGroup, readAck);
        }

        // 1. 并发注册登录 N 用户(ltg 前缀,reset-data.sh 的 lt% 可清)
        LoadTestSupport.TestUser[] users = new LoadTestSupport.TestUser[total];
        ExecutorService regPool = Executors.newFixedThreadPool(Math.min(16, Math.max(4, total)));
        CountDownLatch regDone = new CountDownLatch(total);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            regPool.submit(() -> {
                try { users[idx] = LoadTestSupport.registerAndLogin("ltg", idx, apiBase); }
                catch (Exception e) { if (!quiet) log.warn("注册登录失败[{}]: {}", idx, e.getMessage()); }
                finally { regDone.countDown(); }
            });
        }
        regDone.await();
        regPool.shutdown();
        if (!quiet) {
            long ok = Arrays.stream(users).filter(Objects::nonNull).count();
            log.info("注册登录完成: {}/{} 成功, 耗时 {}ms", ok, total, System.currentTimeMillis() - t0);
        }

        // 2. 每群 owner 建群(owner = 群内第 0 个用户,自动成为成员;members 传其余 S-1 个)
        String[] groupIds = new String[groupCount];
        int[] groupFail = {0};
        ExecutorService groupPool = Executors.newFixedThreadPool(Math.min(8, Math.max(1, groupCount)));
        CountDownLatch groupDone = new CountDownLatch(groupCount);
        for (int g = 0; g < groupCount; g++) {
            final int gIdx = g;
            groupPool.submit(() -> {
                try {
                    int base = gIdx * groupSize;
                    StringBuilder sb = new StringBuilder("{\"name\":\"ltg-group-" + gIdx + "\",\"members\":[");
                    for (int m = 1; m < groupSize; m++) { // 从 1 开始:owner(下标 0)自动加入,不传
                        if (users[base + m] == null) continue;
                        sb.append('"').append(users[base + m].userId).append('"');
                        if (m < groupSize - 1) sb.append(',');
                    }
                    sb.append("]}");
                    String body = LoadTestSupport.post(apiBase + "/api/groups", sb.toString(), users[base].token);
                    CreateGroupResp resp = JsonUtil.fromJson(body, CreateGroupResp.class);
                    if (resp != null && resp.success) {
                        groupIds[gIdx] = resp.groupId;
                        allStats.add(new GroupStats(resp.groupId, groupSize));
                    } else {
                        groupFail[0]++;
                        if (!quiet) log.warn("建群失败[{}]: {}", gIdx, body);
                    }
                } catch (Exception e) {
                    groupFail[0]++;
                    if (!quiet) log.warn("建群异常[{}]: {}", gIdx, e.getMessage());
                } finally {
                    groupDone.countDown();
                }
            });
        }
        groupDone.await();
        groupPool.shutdown();
        int created = groupCount - groupFail[0];
        if (created == 0) {
            log.error("所有群创建失败,退出");
            System.exit(1);
        }
        if (!quiet) log.info("建群完成: {}/{} 成功", created, groupCount);

        // 3. 成员建连 + 握手(每群成员共享该群 GroupStats)
        EventLoopGroup group = new NioEventLoopGroup(Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors())));
        List<GroupMember> members = new ArrayList<>();
        CountDownLatch allConnected = new CountDownLatch(total);
        for (int i = 0; i < total; i++) {
            LoadTestSupport.TestUser u = users[i];
            if (u == null) { allConnected.countDown(); continue; }
            int gIdx = i / groupSize;
            GroupStats gs = null;
            for (GroupStats s : allStats) {
                if (s.groupId.equals(groupIds[gIdx])) { gs = s; break; }
            }
            if (gs == null) { allConnected.countDown(); continue; } // 群创建失败的成员不建连
            GroupMember m = new GroupMember(u, gs, i % groupSize < sendersPerGroup);
            members.add(m);
            new PerHandler(group, m, ports[i % ports.length], allConnected);
        }
        allConnected.await(60, TimeUnit.SECONDS);
        if (!quiet) log.info("握手完成: 成功={} 失败={}", handshakeOk.sum(), handshakeFail.sum());

        // 4. 全部握手完成后统一开始灌群消息
        for (GroupMember m : members) m.startSender();

        long t1 = System.currentTimeMillis();
        long lastReport = -1;
        while (System.currentTimeMillis() - t1 < durationSec * 1000L) {
            Thread.sleep(1000);
            long elapsed = (System.currentTimeMillis() - t1) / 1000;
            if (elapsed - lastReport >= 5) {
                lastReport = elapsed;
                reportProgress((int) elapsed);
            }
        }
        reportProgress((int) durationSec);

        // 5. 关闭 + 汇总
        for (GroupMember m : members) m.close();
        group.shutdownGracefully().sync();

        long sentN = 0, ackedN = 0, recvN = 0, dupN = 0, oooN = 0, gapN = 0;
        System.out.println("========== QuantumLink 群扇出压测结果 ==========");
        System.out.println("配置: 群大小=" + groupSize + " 群数=" + created + " 每群速率=" + msgPerSec
                + "/s 时长=" + durationSec + "s 发送者/群=" + sendersPerGroup + " readAck=" + readAck);
        System.out.println("连接: 总=" + total + " 握手成功=" + handshakeOk.sum() + " 失败=" + handshakeFail.sum()
                + " 压测期掉线=" + closedConns.sum());
        for (GroupStats s : allStats) {
            long st = s.sent.sum(), ak = s.acked.sum(), rc = s.recv.sum(), dp = s.dupRecv.sum();
            long[] al = s.ackLats.stream().mapToLong(Long::longValue).toArray();
            long[] el = s.e2eLats.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(al); Arrays.sort(el);
            long theory = st * (long) (s.size - 1); // 每条消息推给除发送者外的 S-1 人
            System.out.printf("群 %s (S=%d): 发送=%d ACK=%d(%.1f%%) ackP99=%dms | 实收=%d 重复=%d 不重率=%.4f 送达率=%.1f%% e2eP99=%dms 乱序=%d 缺号=%d%n",
                    s.groupId, s.size, st, ak, st > 0 ? 100.0 * ak / st : 0,
                    (long) LoadTestSupport.pct(al, 99),
                    rc, dp, rc > 0 ? 1.0 - (double) dp / rc : 0,
                    theory > 0 ? 100.0 * s.uniqRecv() / theory : 0,
                    (long) LoadTestSupport.pct(el, 99), s.ooo.sum(), s.gap.sum());
            sentN += st; ackedN += ak; recvN += rc; dupN += dp; oooN += s.ooo.sum(); gapN += s.gap.sum();
        }
        long theoryN = sentN * (long) (groupSize - 1);
        System.out.printf("合计: 发送=%d ACK=%d 实收=%d 重复=%d 扇出倍数=%.1f(理论 %d) 送达率=%.1f%% 不重率=%.4f 乱序=%d 缺号=%d%n",
                sentN, ackedN, recvN, dupN,
                sentN > 0 ? (double) recvN / sentN : 0, theoryN,
                theoryN > 0 ? 100.0 * (recvN - dupN) / theoryN : 0,
                recvN > 0 ? 1.0 - (double) dupN / recvN : 0, oooN, gapN);
        System.out.println("================================================");
        System.exit(0);
    }

    /** 群成员:持有用户 + 所属群统计 + 是否本群发送者 */
    static class GroupMember {
        final LoadTestSupport.TestUser user;
        final GroupStats gs;
        final boolean isSender;
        volatile PerHandler handler;

        GroupMember(LoadTestSupport.TestUser u, GroupStats gs, boolean isSender) {
            this.user = u;
            this.gs = gs;
            this.isSender = isSender;
        }

        void startSender() {
            PerHandler h = handler;
            if (h != null && h.channel != null) {
                h.channel.eventLoop().execute(h::startSender);
            }
        }

        void close() {
            PerHandler h = handler;
            if (h != null) h.statsClosed = true;
            if (h != null && h.channel != null) h.channel.close();
        }
    }

    /** 单连接的帧处理:握手 → 心跳 → (发送者)灌群消息 → 接收统计 + 可选 READ_ACK */
    static class PerHandler extends SimpleChannelInboundHandler<ImFrame> {
        final GroupMember member;
        final CountDownLatch latch;
        /** 本连接已见 serverMsgId(按连接去重:同批消息各成员都收,共享 Set 会误判重复) */
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<String, Long> convLastSeq = new ConcurrentHashMap<>();
        Channel channel;
        int msgSeq = 0;
        volatile boolean connected = false;
        volatile boolean statsClosed = false;

        PerHandler(EventLoopGroup group, GroupMember m, int port, CountDownLatch latch) {
            this.member = m;
            this.latch = latch;
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            member.handler = PerHandler.this;
                            ch.pipeline().addLast(new ImFrameDecoder(), new ImFrameEncoder(), PerHandler.this);
                        }
                    });
            b.connect(host, port);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            this.channel = ctx.channel();
            HandshakePayload hs = new HandshakePayload();
            hs.setToken(member.user.token);
            hs.setDeviceId(member.user.deviceId);
            ctx.writeAndFlush(ProtocolUtil.buildFrame(FrameType.HANDSHAKE, hs));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ImFrame frame) {
            switch (frame.getType()) {
                case HANDSHAKE_ACK -> onHandshakeAck(frame);
                case MSG_ACK -> onAck(frame);
                case MSG -> onMsg(frame);
                case PONG -> { /* 心跳应答 */ }
                default -> { }
            }
        }

        private void onHandshakeAck(ImFrame frame) {
            HandshakeAckPayload ack = ProtocolUtil.parseBody(frame, HandshakeAckPayload.class);
            if (ack != null && ack.isSuccess()) {
                connected = true;
                handshakeOk.increment();
                startHeartbeat();
            } else {
                handshakeFail.increment();
            }
            latch.countDown();
        }

        /** 群 ack 含完整扇出段(落库→成员查询→nodeOf→扇出 MQ→才回 ACK),ack 延迟 = 扇出成本度量 */
        private void onAck(ImFrame frame) {
            AckPayload ack = ProtocolUtil.parseBody(frame, AckPayload.class);
            if (ack == null || ack.getAckType() == null || ack.getAckType() != AckType.STORE) return;
            Long t = member.gs.pending.remove(ack.getClientMsgId());
            if (t != null) {
                member.gs.ackLats.add((System.nanoTime() - t) / 1_000_000);
                member.gs.acked.increment();
            } else {
                member.gs.dupAck.increment();
            }
        }

        /** 接收统计(按群隔离)+ 可选 READ_ACK 群已读上报 */
        private void onMsg(ImFrame frame) {
            MessagePayload m = ProtocolUtil.parseBody(frame, MessagePayload.class);
            if (m == null || m.getServerMsgId() == null) return;
            GroupStats gs = member.gs;
            gs.recv.increment();
            if (!seen.add(m.getServerMsgId())) gs.dupRecv.increment(); // 本连接内重复
            if (m.getClientTime() != null) {
                gs.e2eLats.add(System.currentTimeMillis() - m.getClientTime());
            }
            if (m.getSeq() != null) {
                convLastSeq.compute(m.getConversationId() == null ? gs.groupId : m.getConversationId(), (k, last) -> {
                    if (last == null) return m.getSeq();
                    if (m.getSeq() <= last) gs.ooo.increment();
                    else if (m.getSeq() > last + 1) gs.gap.increment();
                    return m.getSeq();
                });
            }
            if (readAck && m.getSeq() != null) {
                ReadReportPayload rr = new ReadReportPayload();
                rr.setConversationId(m.getConversationId());
                rr.setUntilSeq(m.getSeq()); // 读到 seq X = ≤X 全已读(水位单调推进)
                channel.writeAndFlush(ProtocolUtil.buildFrame(FrameType.READ_ACK, rr));
            }
        }

        /** 心跳:10s + 0-5s jitter */
        private void startHeartbeat() {
            long jitter = ThreadLocalRandom.current().nextLong(0, 5000);
            channel.eventLoop().scheduleAtFixedRate(() -> {
                if (channel.isActive()) {
                    channel.writeAndFlush(ProtocolUtil.buildFrame(FrameType.PING, new byte[0]));
                }
            }, 10_000 + jitter, 10_000, TimeUnit.MILLISECONDS);
        }

        /** 每群独立发消息:conversationId=receiverId=groupId(读扩散,必须显式填 g_ 前缀) */
        private void startSender() {
            if (!member.isSender) return; // 非本群发送者只收不发
            long intervalMs = Math.max(1, 1000 / Math.max(1, msgPerSec));
            channel.eventLoop().scheduleAtFixedRate(this::sendGroupMessage, 500, intervalMs, TimeUnit.MILLISECONDS);
        }

        private void sendGroupMessage() {
            if (!channel.isActive()) return;
            MessagePayload m = new MessagePayload();
            m.setClientMsgId(UUID.randomUUID().toString());
            m.setConversationId(member.gs.groupId);
            m.setReceiverId(member.gs.groupId);
            m.setMsgType("TEXT");
            m.setContent("ltg-" + member.gs.groupId + "-" + (msgSeq++));
            m.setClientTime(System.currentTimeMillis());
            member.gs.pending.put(m.getClientMsgId(), System.nanoTime());
            member.gs.sent.increment();
            channel.writeAndFlush(ProtocolUtil.buildFrame(FrameType.MSG, m));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!statsClosed && connected) closedConns.increment();
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

    /** 每 5s 实时进度(GPROGRESS 行) */
    static void reportProgress(int elapsedSec) {
        long sentN = 0, ackedN = 0, recvN = 0, dupN = 0;
        List<Long> e2eAll = new ArrayList<>();
        for (GroupStats s : allStats) {
            sentN += s.sent.sum(); ackedN += s.acked.sum();
            recvN += s.recv.sum(); dupN += s.dupRecv.sum();
            s.e2eLats.forEach(e2eAll::add);
        }
        long[] e2e = e2eAll.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(e2e);
        System.out.printf("GPROGRESS|%d|%d|%d|%d|%d|%.0f|%.0f|%.0f%n",
                elapsedSec, sentN, ackedN, recvN, dupN,
                LoadTestSupport.pct(e2e, 50), LoadTestSupport.pct(e2e, 90), LoadTestSupport.pct(e2e, 99));
    }

    /** 位置参数 + --read-ack flag */
    static void parseArgs(String[] args) {
        int pos = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--read-ack".equals(a)) { readAck = true; continue; }
            switch (pos++) {
                case 0 -> groupSize = Integer.parseInt(a);
                case 1 -> groupCount = Integer.parseInt(a);
                case 2 -> msgPerSec = Integer.parseInt(a);
                case 3 -> durationSec = Integer.parseInt(a);
                case 4 -> host = a;
                case 5 -> ports = Arrays.stream(a.split(",")).mapToInt(Integer::parseInt).toArray();
                case 6 -> sendersPerGroup = Integer.parseInt(a);
                case 7 -> quiet = Boolean.parseBoolean(a);
                default -> throw new IllegalArgumentException("多余参数: " + a + "(flag: --read-ack)");
            }
        }
    }
}
