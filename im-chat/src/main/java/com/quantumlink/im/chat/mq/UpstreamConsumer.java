package com.quantumlink.im.chat.mq;

import com.quantumlink.im.chat.service.MessageService;
import com.quantumlink.im.common.protocol.MessagePayload;
import com.quantumlink.im.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;

/**
 * 上行消费者:消费 {@code client2server}(connect 生产的上行消息)。
 *
 * <p>消费逻辑:反序列化 MessagePayload → 交给 MessageService(幂等+落库+seq+回 ACK)。
 *
 * <p>MVP 用并发消费(MessageListenerConcurrently);Phase 2 做有序性时,
 * 同一会话的消息需同一队列串行消费(MessageListenerOrderly + 队列选择器)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamConsumer {

    private final MessageService messageService;
    private DefaultMQPushConsumer consumer;

    @Value("${rocketmq.name-server}")
    private String namesrvAddr;

    @Value("${rocketmq.topics.upstream}")
    private String upstreamTopic;

    @PostConstruct
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer("im-chat-consumer");
            consumer.setNamesrvAddr(namesrvAddr);
            consumer.subscribe(upstreamTopic, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    try {
                        String json = new String(msg.getBody(), StandardCharsets.UTF_8);
                        MessagePayload payload = JsonUtil.fromJson(json, MessagePayload.class);
                        if (payload == null) {
                            log.warn("parse upstream message failed, skip");
                            continue;
                        }
                        messageService.handleUpstream(payload);
                    } catch (Exception e) {
                        log.error("handle upstream message error, will retry: msgId={}", msg.getMsgId(), e);
                        // 返回 RECONSUME_LATER 让 MQ 重投(配合幂等去重,重复投递安全)
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            log.info("upstream consumer started: topic={}", upstreamTopic);
        } catch (Exception e) {
            throw new IllegalStateException("start upstream consumer failed", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
