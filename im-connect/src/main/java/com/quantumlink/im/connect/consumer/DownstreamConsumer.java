package com.quantumlink.im.connect.consumer;

import com.quantumlink.im.common.protocol.AckPayload;
import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.protocol.FrameType;
import com.quantumlink.im.common.protocol.MessagePayload;
import com.quantumlink.im.common.util.JsonUtil;
import com.quantumlink.im.common.util.ProtocolUtil;
import com.quantumlink.im.connect.config.ConnectConfig;
import com.quantumlink.im.connect.service.ChannelManager;
import io.netty.channel.Channel;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * 下行消费者:消费 {@code server2client}(chat 发来的消息/回执),推给目标连接。
 *
 * <p>统一信封 {@link DownstreamEnvelope}:只解析一种结构,按 contentType 分发:
 * <ul>
 *   <li>ACK → 包装成 MSG_ACK 帧,推给发送方;</li>
 *   <li>MSG → 包装成 MSG 帧,推给接收方。</li>
 * </ul>
 *
 * <p>推给谁:envelope.targetUserId(+targetDeviceId)。deviceId 为空 = 该用户所有在线设备(多端全推)。
 *
 * <p>注意:推送写在 MQ 消费线程,写 Channel 用 {@code channel.eventLoop().execute(...)}
 * 保证线程安全(不直接跨线程写 Netty Channel)。
 */
public class DownstreamConsumer {
    private static final Logger log = LoggerFactory.getLogger(DownstreamConsumer.class);

    private final DefaultMQPushConsumer consumer;

    public DownstreamConsumer(ConnectConfig config, String nodeId) {
        // 每个节点用独立的 consumer group(group 名带 nodeId——冒号/点替换为下划线,
        // RocketMQ group 名只允许 [%|a-zA-Z0-9_-])。
        // 若所有节点共用同一 group,RocketMQ 会在 group 内做消息负载均衡(分摊),
        // 导致"发给 B 节点的消息被 A 节点消费"——而 A 节点本地没有 B 的 channel,
        // 消息被丢弃。独立 group 保证每条 tag 消息只被对应节点消费。
        this.consumer = new DefaultMQPushConsumer(
                "im-connect-consumer-" + nodeId.replaceAll("[:.]", "_"));
        this.consumer.setNamesrvAddr(config.namesrvAddr);
        try {
            // 水平扩展:只订阅本节点的 tag。chat 发下行时按目标节点 nodeId 打 tag,
            // Broker 端过滤后只有本节点收到,其他节点零开销(不广播)。
            this.consumer.subscribe("server2client", nodeId);
            this.consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    try {
                        handle(msg);
                    } catch (Exception e) {
                        log.error("handle downstream message error, retry later: msgId={}", msg.getMsgId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            this.consumer.start();
            log.info("downstream consumer started: topic=server2client tag={}", nodeId);
        } catch (Exception e) {
            throw new IllegalStateException("start downstream consumer failed", e);
        }
    }

    private void handle(MessageExt msg) {
        String json = new String(msg.getBody(), StandardCharsets.UTF_8);
        DownstreamEnvelope envelope = JsonUtil.fromJson(json, DownstreamEnvelope.class);
        boolean hasTargets = envelope != null && envelope.getTargets() != null && !envelope.getTargets().isEmpty();
        if (envelope == null || (envelope.getTo() == null && !hasTargets)) {
            log.warn("bad downstream envelope, skip");
            return;
        }

        // 定位目标连接:单播(to)或群播(targets)
        Collection<Channel> channels = new java.util.ArrayList<>();
        if (envelope.getTargets() != null && !envelope.getTargets().isEmpty()) {
            // 群播:遍历该节点上的目标成员,每个用户的所有在线设备都推(多端全推)
            for (String uid : envelope.getTargets()) {
                channels.addAll(ChannelManager.getAll(uid));
            }
        } else if (envelope.getDeviceId() != null) {
            Channel ch = ChannelManager.get(envelope.getTo(), envelope.getDeviceId());
            if (ch != null) channels.add(ch);
        } else {
            channels.addAll(ChannelManager.getAll(envelope.getTo()));
        }

        if (channels.isEmpty()) {
            // 接收方离线:消息已落库,上线走增量拉取(Phase 2)。这里不推送。
            log.info("target offline, skip push: user={} type={}", envelope.getTo(), envelope.getType());
            return;
        }

        // 按类型包装成客户端帧,推给每个目标 channel
        FrameType frameType = switch (envelope.getType()) {
            case DownstreamEnvelope.TYPE_ACK -> FrameType.MSG_ACK;
            case DownstreamEnvelope.TYPE_MSG -> FrameType.MSG;
            case DownstreamEnvelope.TYPE_READ -> FrameType.MSG_ACK; // 已读事件:作为服务端回执推给发送方
            case DownstreamEnvelope.TYPE_GROUP_READ -> FrameType.MSG_ACK; // 群已读计数更新:推给消息发送者
            default -> {
                log.warn("unknown type: {}", envelope.getType());
                yield null;
            }
        };
        if (frameType == null) {
            return;
        }

        // data 是反序列化后的对象(AckPayload/MessagePayload),序列化后作为客户端帧 body
        String dataJson = JsonUtil.toJson(envelope.getData());
        for (Channel channel : channels) {
            if (!channel.isActive()) {
                continue;
            }
            // 背压保护:弱网连接写缓冲超过高水位(outboundBuffer 积压)→ 不可写。
            // 跳过本次推送,不往 EventLoop 提交写任务,防止这条弱连接无限吃内存拖垮整个进程。
            // 消息已落库(MySQL),客户端断线重连后增量拉取兜底,不丢——"推送尽力而为 + 拉取保证正确"。
            if (!channel.isWritable()) {
                log.warn("channel not writable, skip push (rely on incremental pull): user={} type={}",
                        envelope.getTo(), envelope.getType());
                continue;
            }
            // 写 Channel 必须在 eventLoop 线程,保证线程安全
            channel.eventLoop().execute(() -> {
                try {
                    channel.writeAndFlush(ProtocolUtil.buildFrame(frameType, dataJson.getBytes(StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    log.error("push to channel failed: user={}", envelope.getTo(), e);
                }
            });
        }
        log.info("downstream pushed: user={} devices={} type={}", envelope.getTo(), channels.size(), envelope.getType());
    }

    public void shutdown() {
        consumer.shutdown();
    }
}
