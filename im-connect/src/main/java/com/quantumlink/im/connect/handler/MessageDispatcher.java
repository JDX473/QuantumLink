package com.quantumlink.im.connect.handler;

import com.quantumlink.im.common.protocol.ImFrame;
import com.quantumlink.im.common.protocol.MessagePayload;
import com.quantumlink.im.common.util.ConversationIdUtil;
import com.quantumlink.im.common.util.JsonUtil;
import com.quantumlink.im.common.util.ProtocolUtil;
import com.quantumlink.im.connect.service.ChannelManager;
import com.quantumlink.im.connect.service.ConnectionContext;
import com.quantumlink.im.connect.service.SessionRegistry;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** per-会话串行执行器:conversationId → 单线程 executor,按 FIFO 依次 produce */
    private final ConcurrentHashMap<String, ExecutorService> conversationExecutors = new ConcurrentHashMap<>();
    private final AtomicInteger executorSeq = new AtomicInteger(0);

    public MessageDispatcher(SessionRegistry sessionRegistry, UpstreamProducer upstreamProducer) {
        this.sessionRegistry = sessionRegistry;
        this.upstreamProducer = upstreamProducer;
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

        // 取该会话的串行执行器(不存在则创建单线程 executor)
        ExecutorService executor = conversationExecutors.computeIfAbsent(conversationId,
                k -> Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "conv-" + k.substring(0, Math.min(8, k.length())) + "-" + executorSeq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }));

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
}
