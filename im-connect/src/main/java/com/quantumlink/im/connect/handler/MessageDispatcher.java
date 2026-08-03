package com.quantumlink.im.connect.handler;

import com.quantumlink.im.common.protocol.MessagePayload;
import com.quantumlink.im.common.util.JsonUtil;
import com.quantumlink.im.connect.service.ChannelManager;
import com.quantumlink.im.connect.service.ConnectionContext;
import com.quantumlink.im.connect.service.SessionRegistry;
import io.netty.channel.Channel;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 消息调度器:连接层的业务入口。
 *
 * <p>职责:
 * <ul>
 *   <li>上行:把客户端消息发布到 RocketMQ {@code client2server}(chat 消费做落库);</li>
 *   <li>断连清理:移除本地 channel + 清 Redis 会话;</li>
 *   <li>下行(MVP Phase 2):从 {@code server2client} 消费消息推给目标连接。</li>
 * </ul>
 *
 * <p>上行发 MQ 用异步 send({@code asyncSend} + callback),不在调用线程阻塞。
 */
public class MessageDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final SessionRegistry sessionRegistry;
    private final UpstreamProducer upstreamProducer;

    public MessageDispatcher(SessionRegistry sessionRegistry, UpstreamProducer upstreamProducer) {
        this.sessionRegistry = sessionRegistry;
        this.upstreamProducer = upstreamProducer;
    }

    /**
     * 上行:客户端消息 → RocketMQ(chat 消费落库)。
     * 已在业务线程池中执行,此处可安全阻塞。
     */
    public void dispatchUpstream(String userId, String deviceId, MessagePayload payload) {
        // 补齐发送者身份(防止客户端伪造 senderId)
        payload.setSenderId(userId);

        String json = JsonUtil.toJson(payload);
        upstreamProducer.sendAsync(json, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.debug("upstream sent: user={} msg={}", userId, payload.getClientMsgId());
            }

            @Override
            public void onException(Throwable e) {
                // 发 MQ 失败:Phase 2 会在这里触发重传/补偿
                log.error("upstream send failed: user={} clientMsgId={}", userId, payload.getClientMsgId(), e);
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
