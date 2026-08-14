package com.quantumlink.im.chat.mq;

import com.quantumlink.im.chat.service.MessageService;
import com.quantumlink.im.chat.util.PerfStats;
import com.quantumlink.im.common.protocol.MessagePayload;
import com.quantumlink.im.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 上行消费者:消费 {@code client2server}(connect 生产的上行消息)。
 *
 * <p>消费逻辑:反序列化 MessagePayload → 交给 MessageService(幂等+落库+seq+回 ACK)。
 *
 * <p><b>队列级串行消费(MessageListenerOrderly)—— 有序性关键</b>:
 * connect 已按会话选同一队列,所以"同一队列"= "同一会话"。
 * Orderly 保证同一队列的消息严格串行消费,不同队列(不同会话)并行。
 * 配合 Redis INCR 取号,同一会话的 seq 分配顺序 = 消息发送顺序。
 * 若用并发消费(MessageListenerConcurrently),同一会话的消息被多线程同时
 * 处理,Redis INCR 的分配顺序就不再等于发送顺序 → 会话内乱序。
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
            consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
                // Orderly:同一队列(同一会话)的消息串行处理,天然保序
                for (MessageExt msg : msgs) {
                    try {
                        long consumeStart = System.nanoTime();
                        long nowMs = System.currentTimeMillis();
                        String json = new String(msg.getBody(), StandardCharsets.UTF_8);
                        MessagePayload payload = JsonUtil.fromJson(json, MessagePayload.class);
                        if (payload == null) {
                            log.warn("parse upstream message failed, skip");
                            continue;
                        }
                        // 埋点:客户端 → connect produce(bornTimestamp=connect 生产时钟,同机同源)
                        if (payload.getClientTime() != null) {
                            PerfStats.recordMs("cli_to_broker", msg.getBornTimestamp() - payload.getClientTime());
                        }
                        // 埋点:上行 MQ 投递(connect produce → chat 消费)
                        PerfStats.recordMs("mq_up", nowMs - msg.getBornTimestamp());
                        // 再拆:produce→broker 落盘(storeTimestamp)vs broker→消费唤醒
                        PerfStats.recordMs("mq_broker", msg.getStoreTimestamp() - msg.getBornTimestamp());
                        PerfStats.recordMs("mq_wakeup", nowMs - msg.getStoreTimestamp());
                        messageService.handleUpstream(payload);
                        PerfStats.record("orderly_total", System.nanoTime() - consumeStart);
                    } catch (Exception e) {
                        log.error("handle upstream message error, will retry: msgId={}", msg.getMsgId(), e);
                        // Orderly 重试:返回 SUSPEND_CURRENT_QUEUE_A_MOMENT 稍后重试当前队列
                        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                    }
                }
                return ConsumeOrderlyStatus.SUCCESS;
            });
            consumer.start();
            log.info("upstream consumer started (orderly): topic={}", upstreamTopic);
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
