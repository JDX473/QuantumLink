package com.quantumlink.im.chat.mq;

import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <p><b>水平扩展:MQ tag 精准投递。</b>chat 发下行前查 Redis 会话表
 * {@code im:session:{userId}:{deviceId}} 定位目标节点,用 nodeId 作为 MQ tag 发送。
 * 只有订阅了该 tag 的 connect 节点消费(即目标节点),其他节点零开销。
 *
 * <p>统一信封让 connect 只解析一种结构;加新下行类型(如 DELIVER)只需扩展 contentType。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownstreamProducer {

    /** Redis 会话表前缀(与 connect SessionRegistry 共用同一 key) */
    private static final String SESSION_PREFIX = "im:session:";

    private final StringRedisTemplate redisTemplate;

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
     * 发送下行消息(统一信封:{type, to, data}),按目标节点 tag 精准投递。
     *
     * @param targetUserId  推给哪个用户
     * @param targetDeviceId 目标设备;null = 该用户所有在线设备(多端全推)
     * @param contentType    DownstreamEnvelope.TYPE_ACK / TYPE_MSG
     * @param data           AckPayload 或 MessagePayload 对象(connect 不解析其内部)
     */
    public void sendEnvelope(String targetUserId, String targetDeviceId, String contentType, Object data) {
        DownstreamEnvelope envelope = new DownstreamEnvelope();
        envelope.setTo(targetUserId);
        envelope.setDeviceId(targetDeviceId);
        envelope.setType(contentType);
        envelope.setData(data);
        send(JsonUtil.toJson(envelope), targetUserId, targetDeviceId);
    }

    /**
     * 群播发送:按成员 userId 定位节点 → 按 nodeId 分组 → 每节点一条 targets 信封。
     *
     * <p>为什么按节点聚合:100 人群分散在 2 个 connect 节点,若每人一条 MQ 要发 100 条;
     * 聚合后每节点一条(2 条),connect 端遍历 targets 推给本节点上的所有成员,
     * 大幅减少 MQ 扇出。离线成员(会话表无记录)不投递,上线走增量拉取。
     *
     * @param targetUserIds 目标成员 userId 列表(去重后的在线成员)
     * @param contentType   DownstreamEnvelope.TYPE_ACK / TYPE_MSG
     * @param data          AckPayload 或 MessagePayload 对象
     */
    public void sendGroupEnvelope(java.util.List<String> targetUserIds, String contentType, Object data) {
        // 按 nodeId 分组:nodeId → 该节点上的成员列表
        java.util.Map<String, java.util.List<String>> byNode = new java.util.LinkedHashMap<>();
        for (String uid : targetUserIds) {
            // 查会话表定位该用户节点(多端全推:任一在线设备即可,同一用户多端同节点)
            var keys = redisTemplate.keys(SESSION_PREFIX + uid + ":*");
            if (keys == null || keys.isEmpty()) {
                continue; // 离线成员:不推送,上线拉取
            }
            String nodeId = null;
            for (Object k : keys) {
                String v = redisTemplate.opsForValue().get((String) k);
                if (v != null) { nodeId = v; break; }
            }
            if (nodeId == null) continue;
            byNode.computeIfAbsent(nodeId, k -> new java.util.ArrayList<>()).add(uid);
        }

        for (java.util.Map.Entry<String, java.util.List<String>> e : byNode.entrySet()) {
            DownstreamEnvelope envelope = new DownstreamEnvelope();
            envelope.setTargets(e.getValue());
            envelope.setType(contentType);
            envelope.setData(data);
            Message msg = new Message(downstreamTopic, JsonUtil.toJson(envelope).getBytes(StandardCharsets.UTF_8));
            try {
                msg.setTags(e.getKey());
                SendResult result = producer.send(msg);
                log.info("group downstream sent: topic={} tag={} members={} msgId={}",
                        downstreamTopic, e.getKey(), e.getValue().size(), result.getMsgId());
            } catch (Exception ex) {
                log.error("group downstream send failed: tag={} members={}", e.getKey(), e.getValue().size(), ex);
            }
        }
    }

    /**
     * 发送下行消息,查会话表定位目标节点并打 tag。
     *
     * <p>水平扩展核心:同一用户的所有在线设备 → 各自的节点 tag。
     * 多端时 targetDeviceId 为 null,需要查询该用户所有设备的会话记录。
     * MVP 先支持单设备(直接查 userId:deviceId),多端全推后续增强。
     */
    private void send(String json, String targetUserId, String targetDeviceId) {
        // 查 Redis 会话表定位目标节点:im:session:{userId}:{deviceId} → nodeId
        String sessionKey = SESSION_PREFIX + targetUserId + ":";
        String nodeId = null;
        if (targetDeviceId != null) {
            nodeId = redisTemplate.opsForValue().get(sessionKey + targetDeviceId);
        } else {
            // 多端全推:查该用户所有设备的会话(扫描 im:session:{userId}:*)
            var keys = redisTemplate.keys(sessionKey + "*");
            if (keys != null && !keys.isEmpty()) {
                nodeId = redisTemplate.opsForValue().get(keys.iterator().next());
            }
        }

        if (nodeId == null) {
            log.info("target offline or not registered, skip downstream push: user={} device={}",
                    targetUserId, targetDeviceId);
            return;
        }

        Message msg = new Message(downstreamTopic, json.getBytes(StandardCharsets.UTF_8));
        try {
            // 打目标节点 tag:只有订阅该 tag 的 connect 节点消费
            msg.setTags(nodeId);
            SendResult result = producer.send(msg);
            log.info("downstream sent: topic={} tag={} msgId={}", downstreamTopic, nodeId, result.getMsgId());
        } catch (Exception e) {
            log.error("downstream send failed: user={} tag={}", targetUserId, nodeId, e);
            // Phase 2:ACK 发送失败要补偿(重试/记录未确认消息)
        }
    }

    @PreDestroy
    public void shutdown() {
        producer.shutdown();
    }
}
