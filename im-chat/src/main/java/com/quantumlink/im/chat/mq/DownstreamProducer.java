package com.quantumlink.im.chat.mq;

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
 * <p>承载两类下行:
 * <ul>
 *   <li>ACK 回执(ACK-STORE 等):发给发送方;</li>
 *   <li>消息推送(Phase 2):发给接收方。</li>
 * </ul>
 *
 * <p>MVP 单节点:消息直接发到下游 topic,connect 消费后推给目标 channel。
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

    /** 发送 ACK 回执(同步,MVP 足够;Phase 2 可改异步) */
    public void sendAck(Object ack) {
        send(JsonUtil.toJson(ack));
    }

    /** 发送下行消息 */
    public void send(Object body) {
        send(JsonUtil.toJson(body));
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
