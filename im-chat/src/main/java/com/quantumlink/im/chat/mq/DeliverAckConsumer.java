package com.quantumlink.im.chat.mq;

import com.quantumlink.im.chat.entity.Message;
import com.quantumlink.im.chat.mapper.MessageMapper;
import com.quantumlink.im.chat.service.OutboxService;
import com.quantumlink.im.common.protocol.AckPayload;
import com.quantumlink.im.common.protocol.AckType;
import com.quantumlink.im.common.protocol.DownstreamEnvelope;
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
 * 已送达回执消费者:消费 {@code deliver_ack}(接收方 B 收到消息后由 connect 转发)。
 *
 * <p>处理:更新消息状态 SENT → DELIVERED,然后回 DELIVER 回执给发送方 A,
 * 让 A 知道"对方已送达"。
 *
 * <p>这是双 ACK 的第二跳:
 * <ul>
 *   <li>STORE(第一跳):chat 落库成功,消息安全入库(不丢);</li>
 *   <li>DELIVER(第二跳):接收方客户端已收到,发送方看到"已送达"。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliverAckConsumer {

    private final MessageMapper messageMapper;
    private final DownstreamProducer downstreamProducer;
    private final OutboxService outboxService;
    private DefaultMQPushConsumer consumer;

    @Value("${rocketmq.name-server}")
    private String namesrvAddr;

    @PostConstruct
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer("im-chat-deliver-consumer");
            consumer.setNamesrvAddr(namesrvAddr);
            consumer.subscribe("client2signal", "*"); // 信令通道:DELIVER_ACK/READ_ACK 都在这,按字段区分
            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    try {
                        handle(msg);
                    } catch (Exception e) {
                        log.error("handle deliver ack error, retry later: msgId={}", msg.getMsgId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            log.info("deliver ack consumer started: topic=client2signal");
        } catch (Exception e) {
            throw new IllegalStateException("start deliver ack consumer failed", e);
        }
    }

    private void handle(MessageExt msg) {
        String json = new String(msg.getBody(), StandardCharsets.UTF_8);
        AckPayload ack = JsonUtil.fromJson(json, AckPayload.class);
        if (ack == null || ack.getServerMsgId() == null) {
            log.warn("bad deliver ack, skip");
            return;
        }

        // ① 解析 serverMsgId(下发为 String 避免 JS 精度丢失,这里转回 Long 做 DB 操作)
        Long serverMsgId;
        try {
            serverMsgId = Long.parseLong(ack.getServerMsgId());
        } catch (NumberFormatException e) {
            log.warn("bad serverMsgId: {}", ack.getServerMsgId());
            return;
        }

        // ② 查消息(群消息的 serverMsgId 在 im_group_message,这里查不到 → 正常跳过,群无 DELIVER 语义)
        Message message = messageMapper.selectById(serverMsgId);
        if (message == null) {
            log.info("message not found for deliver ack (group message or cleaned): serverMsgId={}",
                    ack.getServerMsgId());
            return;
        }

        // ③ 校验回执来源:connect 已盖章 receiverId=连接认证用户(MessageDispatcher.dispatchDeliverAck)。
        //    防伪造 DELIVER_ACK(发送方 A 伪造 B 的回执会提前出箱 + 误标已送达,压掉对 B 的重推)。
        if (!message.getReceiverId().equals(ack.getReceiverId())) {
            log.warn("deliver ack sender mismatch, ignore: msgReceiver={} ackFrom={} serverMsgId={}",
                    message.getReceiverId(), ack.getReceiverId(), ack.getServerMsgId());
            return;
        }

        // ④ 出箱:对方已确认收到(实时推或补拉),发件箱停止重推。不在箱里 = no-op
        outboxService.remove(serverMsgId);

        // ⑤ 更新消息状态:SENT → DELIVERED(接收方已收到)
        int updated = messageMapper.markDelivered(serverMsgId);
        if (updated == 0) {
            log.warn("mark delivered no-op (maybe already delivered): serverMsgId={}", ack.getServerMsgId());
        }

        // ⑥ 回 DELIVER 给发送方 A(统一信封 TYPE_ACK,data 为 AckPayload DELIVER)
        AckPayload deliver = new AckPayload();
        deliver.setAckType(AckType.DELIVER);
        deliver.setClientMsgId(message.getClientMsgId());
        deliver.setServerMsgId(String.valueOf(message.getId()));
        deliver.setSeq(message.getSeq());
        deliver.setReceiverId(message.getReceiverId());
        deliver.setConversationId(message.getConversationId());
        downstreamProducer.sendEnvelope(
                message.getSenderId(), null,
                DownstreamEnvelope.TYPE_ACK, deliver);
        log.info("DELIVER sent to sender={}: serverMsgId={} seq={}", message.getSenderId(), message.getId(), message.getSeq());
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
