package com.quantumlink.im.chat.mq;

import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;

/**
 * 下行生产者:chat → connect({@code server2client})。
 *
 * <p>承载两类下行(统一走 {@link DownstreamEnvelope} 信封):
 * <ul>
 *   <li>ACK 回执(ACK-STORE 等):发给发送方;</li>
 *   <li>消息推送:发给接收方。</li>
 * </ul>
 *
 * <p>统一信封让 connect 只解析一种结构;加新下行类型(如 DELIVER)只需扩展 contentType。
 */
@Slf4j
@Component
public class DownstreamProducer {

    private final DefaultMQProducer producer = new DefaultMQProducer("im-chat-producer");

    @Value("${rocketmq.name-server}")
    private String namesrvAddr;

    @Value("${rocketmq.topics.downstream}")
    private String downstreamTopic;

    @PostConstruct
    public void start() {
        producer.setNamesrvAddr(namesrvAddr);
        producer.setSendMsgTimeout(3000);
        try {
            producer.start();
            log.info("downstream producer started: namesrv={} topic={}", namesrvAddr, downstreamTopic);
        } catch (Exception e) {
            throw new IllegalStateException("start downstream producer failed", e);
        }
    }

    /**
     * 发送下行消息(统一信封:{type, to, data})。
     *
     * @param targetUserId  推给哪个用户
     * @param targetDeviceId 目标设备,null = 多端全推
     * @param contentType    DownstreamEnvelope.TYPE_ACK / TYPE_MSG
     * @param data           AckPayload 或 MessagePayload 对象(connect 不解析其内部)
     */
    public void sendEnvelope(String targetUserId, String targetDeviceId, String contentType, Object data) {
        DownstreamEnvelope envelope = new DownstreamEnvelope();
        envelope.setTo(targetUserId);
        envelope.setDeviceId(targetDeviceId);
        envelope.setType(contentType);
        envelope.setData(data);
        send(JsonUtil.toJson(envelope));
    }

    private void send(String json) {
        Message msg = new Message(downstreamTopic, json.getBytes(StandardCharsets.UTF_8));
        try {
            SendResult result = producer.send(msg);
            log.debug("downstream sent: topic={} msgId={}", downstreamTopic, result.getMsgId());
        } catch (Exception e) {
            log.error("downstream send failed", e);
            // Phase 2:ACK 发送失败要补偿(重试/记录未确认消息)
        }
    }

    @PreDestroy
    public void shutdown() {
        producer.shutdown();
    }
}
