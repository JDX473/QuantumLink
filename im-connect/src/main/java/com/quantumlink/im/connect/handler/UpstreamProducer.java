package com.quantumlink.im.connect.handler;

import com.quantumlink.im.connect.config.ConnectConfig;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 上行 RocketMQ 生产者:客户端消息 → {@code client2server}。
 *
 * <p>用原生 rocketmq-client(非 starter),版本可控、原理讲得清。
 * 异步发送(send + callback),避免阻塞调用线程。
 *
 * <p><b>按会话选同一队列(有序性关键)</b>:同一会话(conversationId)的消息
 * 通过 MessageQueueSelector 哈希到同一队列。RocketMQ 保证同一队列内 FIFO,
 * 加上 chat 端队列内串行消费,同一会话的 seq 分配顺序 = 发送顺序。
 */
public class UpstreamProducer {
    private static final Logger log = LoggerFactory.getLogger(UpstreamProducer.class);

    private final DefaultMQProducer producer;

    public UpstreamProducer(ConnectConfig config) {
        this.producer = new DefaultMQProducer("im-connect-producer");
        this.producer.setNamesrvAddr(config.namesrvAddr);
        this.producer.setSendMsgTimeout(3000);
        try {
            this.producer.start();
            log.info("upstream producer started, namesrv={}", config.namesrvAddr);
        } catch (Exception e) {
            throw new IllegalStateException("start upstream producer failed", e);
        }
    }

    /**
     * 同步发送上行消息,并按会话选同一队列。
     *
     * <p><b>有序性关键:同步发送</b>。只有 send 返回后,下一条消息才开始发,
     * 才能保证"同一会话的消息按调用顺序进入 MQ 队列"。异步发送的话,
     * 回调线程执行 produce,锁无法控制实际入队顺序。
     * 调用方(MessageDispatcher)已按会话加锁,保证同一会话串行调用本方法。
     *
     * @param json            消息 JSON
     * @param conversationId  会话 ID(决定队列)
     * @return 是否发送成功
     */
    public boolean send(String json, String conversationId) {
        return sendToTopic("client2server", json, conversationId);
    }

    /**
     * 同步发送到指定 topic,并按会话选同一队列。
     * 用于 DELIVER_ACK 等需要 chat 单独消费的消息。
     */
    public boolean sendToTopic(String topic, String json, String conversationId) {
        Message msg = new Message(topic, json.getBytes(StandardCharsets.UTF_8));
        try {
            SendResult result = producer.send(msg, new MessageQueueSelector() {
                @Override
                public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
                    int index = Math.abs(conversationId.hashCode()) % mqs.size();
                    return mqs.get(index);
                }
            }, conversationId, 3000);
            log.debug("sent topic={} queue={} msgId={}", topic, result.getMessageQueue().getQueueId(), result.getMsgId());
            return true;
        } catch (Exception e) {
            log.error("send failed topic={}", topic, e);
            return false;
        }
    }

    public void shutdown() {
        producer.shutdown();
    }
}
