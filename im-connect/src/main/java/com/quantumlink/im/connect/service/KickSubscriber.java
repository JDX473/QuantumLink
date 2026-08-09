package com.quantumlink.im.connect.service;

import com.quantumlink.im.common.protocol.KickPayload;
import com.quantumlink.im.common.util.JsonUtil;
import com.quantumlink.im.connect.config.ConnectConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 踢人订阅者:订阅 Redis {@code im:kick} 频道,收到踢人指令后关闭目标连接。
 *
 * <p><b>为什么用 Redis Pub/Sub 做踢人</b>:KICK 是低频、尽力而为的控制消息,
 * 即发即弃的缺点被"删 token 让重连被拒"兜底(踢不到也出局);且免查会话表定位节点。
 *
 * <p><b>广播 + 本地判目标</b>:所有 connect 节点都订阅 im:kick 并收到指令,
 * 但只有<b>持有目标连接</b>的节点才真正关(用本地 ChannelManager 判断),其余忽略。
 * 若不加判断直接关,广播会误杀所有节点的同 userId 连接。
 *
 * <p><b>EventLoop 上清理</b>:清理(remove map / clear context / close)必须在
 * 目标连接的 EventLoop 线程上执行——与该连接的 I/O 不交错,消除竞态。
 */
public class KickSubscriber {
    private static final Logger log = LoggerFactory.getLogger(KickSubscriber.class);

    private static final String KICK_CHANNEL = "im:kick";

    private final RedisClient redisClient;
    private final StatefulRedisPubSubConnection<String, String> connection;

    public KickSubscriber(ConnectConfig config) {
        RedisURI uri = RedisURI.builder()
                .withHost(config.redisHost)
                .withPort(config.redisPort)
                .withTimeout(Duration.ofSeconds(3))
                .build();
        this.redisClient = RedisClient.create(uri);
        this.connection = redisClient.connectPubSub();
        this.connection.addListener(new RedisPubSubListener<String, String>() {
            @Override public void message(String channel, String message) { handle(channel, message); }
            @Override public void message(String pattern, String channel, String message) {}
            @Override public void subscribed(String channel, long count) {}
            @Override public void unsubscribed(String channel, long count) {}
            @Override public void psubscribed(String pattern, long count) {}
            @Override public void punsubscribed(String pattern, long count) {}
        });
        this.connection.sync().subscribe(KICK_CHANNEL);
        log.info("kick subscriber started: channel={}", KICK_CHANNEL);
    }

    private void handle(String channel, String message) {
        try {
            KickPayload kick = JsonUtil.fromJson(message, KickPayload.class);
            if (kick == null || kick.getUserId() == null || kick.getDeviceId() == null) {
                log.warn("bad kick message, skip: {}", message);
                return;
            }
            Channel ch = ChannelManager.get(kick.getUserId(), kick.getDeviceId());
            if (ch == null) {
                return; // 本节点没有目标连接,忽略(广播给所有节点,只有持有者关)
            }
            // 清理必须在目标连接的 EventLoop 线程上:与该连接 I/O 不交错,消除竞态。
            // 注意:不能先 clear ConnectionContext——否则 close 触发 channelInactive → onDisconnect
            // 时 userId 已是 null,onDisconnect 提前返回,不清 Redis 会话表(会话残留,设备列表误判在线)。
            ch.eventLoop().execute(() -> {
                ChannelManager.remove(kick.getUserId(), kick.getDeviceId()); // 停止新下行找到它
                ch.close(); // 触发 onDisconnect → 清 ConnectionContext + Redis 会话表(幂等)
            });
            log.info("kick executed: user={} device={}", kick.getUserId(), kick.getDeviceId());
        } catch (Exception e) {
            log.error("handle kick error: {}", message, e);
        }
    }

    public void shutdown() {
        if (connection != null) connection.close();
        if (redisClient != null) redisClient.shutdown();
    }
}
