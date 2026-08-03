package com.quantumlink.im.connect.handler;

import com.quantumlink.im.connect.config.ConnectConfig;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 上行 RocketMQ 生产者:客户端消息 → {@code client2server}。
 *
 * <p>用原生 rocketmq-client(非 starter),版本可控、原理讲得清。
 * 异步发送(asyncSend + callback),避免阻塞调用线程。
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

    public void sendAsync(String json, SendCallback callback) {
        Message msg = new Message("client2server", json.getBytes(StandardCharsets.UTF_8));
        try {
            // RocketMQ 5.x:异步发送统一用 send(Message, SendCallback, timeout),4.x 的 asyncSend 已更名
            producer.send(msg, callback, 3000);
        } catch (Exception e) {
            log.error("async send failed", e);
            if (callback != null) {
                callback.onException(e);
            }
        }
    }

    public void shutdown() {
        producer.shutdown();
    }
}
