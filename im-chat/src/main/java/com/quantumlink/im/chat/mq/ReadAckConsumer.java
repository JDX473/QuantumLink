package com.quantumlink.im.chat.mq;

import com.quantumlink.im.chat.service.ReadService;
import com.quantumlink.im.common.protocol.ReadReportPayload;
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
 * 已读上报消费者:消费 {@code read_report}(接收方 B 打开会话/看到新消息后,
 * 由 connect 把 READ_ACK 帧转发到本 topic)。
 *
 * <p>处理:交给 {@link ReadService} 单调推进水位 → 持久化 → 推 READ 事件给发送方 A,
 * 让 A 知道"对方已读"。这是可靠投递第三态(存储 → 送达 → 已读)的入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadAckConsumer {

    private final ReadService readService;
    private DefaultMQPushConsumer consumer;

    @Value("${rocketmq.name-server}")
    private String namesrvAddr;

    @PostConstruct
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer("im-chat-read-consumer");
            consumer.setNamesrvAddr(namesrvAddr);
            consumer.subscribe("client2signal", "*"); // 信令通道:DELIVER_ACK/READ_ACK 都在这,按字段区分
            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    try {
                        handle(msg);
                    } catch (Exception e) {
                        log.error("handle read report error, retry later: msgId={}", msg.getMsgId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            log.info("read ack consumer started: topic=client2signal");
        } catch (Exception e) {
            throw new IllegalStateException("start read ack consumer failed", e);
        }
    }

    private void handle(MessageExt msg) {
        String json = new String(msg.getBody(), StandardCharsets.UTF_8);
        ReadReportPayload report = JsonUtil.fromJson(json, ReadReportPayload.class);
        if (report == null || report.getConversationId() == null) {
            log.warn("bad read report, skip");
            return;
        }
        readService.handleReadReport(report);
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
