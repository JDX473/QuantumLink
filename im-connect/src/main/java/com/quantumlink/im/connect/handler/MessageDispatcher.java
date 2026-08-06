package com.quantumlink.im.connect.handler;

import com.quantumlink.im.common.protocol.AckPayload;
import com.quantumlink.im.common.protocol.ImFrame;
import com.quantumlink.im.common.protocol.MessagePayload;
import com.quantumlink.im.common.protocol.ReadReportPayload;
import com.quantumlink.im.common.util.ConversationIdUtil;
import com.quantumlink.im.common.util.JsonUtil;
import com.quantumlink.im.common.util.ProtocolUtil;
import com.quantumlink.im.connect.service.ChannelManager;
import com.quantumlink.im.connect.service.ConnectionContext;
import com.quantumlink.im.connect.service.SessionRegistry;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 消息调度器:连接层的业务入口。
 *
 * <p>职责:
 * <ul>
 *   <li>上行:把客户端消息发布到 RocketMQ {@code client2server}(chat 消费做落库);</li>
 *   <li>断连清理:移除本地 channel + 清 Redis 会话;</li>
 *   <li>下行:从 {@code server2client} 消费消息推给目标连接(见 DownstreamConsumer)。</li>
 * </ul>
 *
 * <p><b>有序性关键:每个会话一个串行执行器(per-conversation FIFO 队列)。</b>
 * 业务线程池并发处理不同消息,若同一会话的两条消息被两个线程并发 produce,
 * 发到 MQ 的顺序可能颠倒 → 后续 seq 分配乱序。
 * 解法:同一会话的消息 submit 到同一个单线程执行器,按"到达顺序(FIFO)"依次 produce,
 * 不同会话用不同执行器,互不阻塞。这是业界标准做法(每会话一个 Processor 线程)。
 */
public class MessageDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final SessionRegistry sessionRegistry;
    private final UpstreamProducer upstreamProducer;

    /**
     * 共享有界线程池(按会话 hash 路由到固定线程)。
     *
     * <p>为什么不用"每会话一个单线程 executor":会话是动态的,executor 只建不回收,
     * 线程数随历史会话数无限增长 → 压测 200 连接线程爆炸(439 线程)。
     *
     * <p>为什么 hash 路由仍保序:同一 conversationId 的 hashCode 确定 → 永远路由到
     * 同一槽位线程 → 该线程 FIFO 串行执行 → 发送顺序 = 到达顺序。不同会话可能
     * 碰撞到同一线程,只损失并行度,不影响各自会话内的顺序。
     *
     * <p>注意:线程池大小必须固定,不能扩缩——取模基数变化会导致同一会话被路由到
     * 不同线程 → 并发处理 → 乱序。
     */
    private final ExecutorService[] conversationExecutors;
    private final int poolSize;

    public MessageDispatcher(SessionRegistry sessionRegistry, UpstreamProducer upstreamProducer) {
        this.sessionRegistry = sessionRegistry;
        this.upstreamProducer = upstreamProducer;
        // 固定线程数:CPU×2(与 bizThreads 一致);有界、不随会话增长
        this.poolSize = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        this.conversationExecutors = new ExecutorService[poolSize];
        for (int i = 0; i < poolSize; i++) {
            final int idx = i;
            this.conversationExecutors[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "conv-slot-" + idx);
                t.setDaemon(true);
                return t;
            });
        }
        log.info("conversation executor pool initialized: {} slots", poolSize);
    }

    /** 会话 → 固定槽位:同一会话永远路由到同一线程 */
    private ExecutorService executorFor(String conversationId) {
        int slot = Math.floorMod(conversationId.hashCode(), poolSize);
        return conversationExecutors[slot];
    }

    /**
     * 消息帧入口(EventLoop 上调用)。
     *
     * <p>这里做两件轻量事:解析出 conversationId(选队列/选会话执行器需要),
     * 然后提交到该会话的单线程 FIFO 队列。EventLoop 按 channelRead 顺序调用本方法,
     * 所以提交顺序 = 客户端发送顺序;单线程队列按 FIFO 处理 → produce 保序。
     */
    public void dispatchFrame(String userId, String deviceId, ImFrame frame) {
        // 先解析出 conversationId(轻量 JSON 读取,不阻塞 EventLoop)
        MessagePayload payload = ProtocolUtil.parseBody(frame, MessagePayload.class);
        if (payload == null) {
            log.warn("parse message failed: user={}", userId);
            return;
        }
        payload.setSenderId(userId);
        String conversationId = payload.getConversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = ConversationIdUtil.build(payload.getSenderId(), payload.getReceiverId());
            payload.setConversationId(conversationId);
        }

        // 路由到固定槽位线程:同一会话永远同一线程(FIFO 保序),线程数固定不增长
        ExecutorService executor = executorFor(conversationId);

        // 提交到 FIFO 队列:按到达顺序串行 produce(不同会话并行)
        String json = JsonUtil.toJson(payload);
        final String finalConversationId = conversationId;
        final String clientMsgId = payload.getClientMsgId();
        executor.execute(() -> {
            try {
                boolean ok = upstreamProducer.send(json, finalConversationId);
                if (!ok) {
                    log.error("upstream send failed: user={} clientMsgId={}", userId, clientMsgId);
                }
            } catch (Exception e) {
                log.error("dispatch upstream error: user={} clientMsgId={}", userId, clientMsgId, e);
            }
        });
    }

    /** 断连清理:移除本地 channel + 清 Redis 会话 */
    public void onDisconnect(Channel channel) {
        String userId = ConnectionContext.userId(channel);
        String deviceId = ConnectionContext.deviceId(channel);
        if (userId == null) {
            return;
        }
        ChannelManager.remove(userId, deviceId);
        sessionRegistry.remove(userId, deviceId);
        ConnectionContext.clear(channel);
        log.info("disconnect cleaned: user={} device={}", userId, deviceId);
    }

    /**
     * 已送达回执(DELIVER_ACK):接收方 B 收到消息后回执。
     * 解析 AckPayload,发到 {@code deliver_ack} topic,chat 消费后更新消息状态并回 DELIVER 给 A。
     */
    public void dispatchDeliverAck(String userId, String deviceId, ImFrame frame) {
        AckPayload ack = ProtocolUtil.parseBody(frame, AckPayload.class);
        if (ack == null || ack.getServerMsgId() == null) {
            log.warn("bad deliver ack, skip: user={}", userId);
            return;
        }
        ack.setReceiverId(userId); // 记录是谁确认的(接收方)

        // 按会话选同一队列(与消息同队列保持顺序);这里 conversationId 由客户端回执携带
        String conversationId = ack.getConversationId();
        String json = JsonUtil.toJson(ack);
        boolean ok = upstreamProducer.sendToTopic("deliver_ack", json, conversationId == null ? "" : conversationId);
        if (!ok) {
            log.error("deliver_ack send failed: user={} serverMsgId={}", userId, ack.getServerMsgId());
        }
    }

    /**
     * 已读上报(READ_ACK):接收方 B 打开会话/看到新消息后上报水位。
     * 解析 ReadReportPayload,补 readerId(从连接上下文,不信任客户端),发到 {@code read_report} topic。
     * chat 消费后推进水位并推 READ 事件给对端 A。
     */
    public void dispatchReadAck(String userId, String deviceId, ImFrame frame) {
        ReadReportPayload report = ProtocolUtil.parseBody(frame, ReadReportPayload.class);
        if (report == null || report.getConversationId() == null || report.getUntilSeq() == null
                || report.getUntilSeq() <= 0) {
            log.warn("bad read report, skip: user={}", userId);
            return;
        }
        report.setReaderId(userId); // 是谁上报的(接收方/读者)

        String conversationId = report.getConversationId();
        String json = JsonUtil.toJson(report);
        boolean ok = upstreamProducer.sendToTopic("read_report", json, conversationId == null ? "" : conversationId);
        if (!ok) {
            log.error("read_report send failed: user={} conv={}", userId, conversationId);
        }
    }
}
