package com.quantumlink.im.connect.service;

import com.quantumlink.im.common.protocol.KickPayload;
import com.quantumlink.im.common.util.JsonUtil;
import com.quantumlink.im.connect.config.ConnectConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
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
 * <p><b>EventLoop 上清理</b>:清理(remove map / 删路由表 / close)必须在
 * 目标连接的 EventLoop 线程上执行——与该连接的 I/O 不交错,消除竞态。
 *
 * <p><b>解耦:先删 Redis 路由表,再关连接</b>(2026-08-09):删路由表不依赖
 * close 成功的 onDisconnect 副作用——若 close 失败,onDisconnect 不触发、路由表残留,
 * 死连接靠心跳(getNode 查得到)续命,兜不住。改为踢人时<b>主动先删</b>
 * sessionRegistry(Redis 路由表),再删本地 ChannelManager,最后 close;
 * close 失败(isActive)重试一次,仍失败由心跳兜底(路由表已删 → getNode=null → 关连接)。
 */
public class KickSubscriber {
    private static final Logger log = LoggerFactory.getLogger(KickSubscriber.class);

    private static final String KICK_CHANNEL = "im:kick";

    private final RedisClient redisClient;
    private final StatefulRedisPubSubConnection<String, String> connection;
    private final SessionRegistry sessionRegistry;

    public KickSubscriber(ConnectConfig config, SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
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
            // 时 userId 已是 null,onDisconnect 提前返回(虽路由表已前置删,仍保语义干净)。
            ch.eventLoop().execute(() -> {
                // 解耦:主动先删 Redis 路由表(不再依赖 close 成功触发 onDisconnect 才删)。
                // close 失败时路由表已删 → 心跳 getNode=null → 关连接,死连接兜得住。
                sessionRegistry.remove(kick.getUserId(), kick.getDeviceId());
                ChannelManager.remove(kick.getUserId(), kick.getDeviceId()); // 停止新下行找到它
                closeWithRetry(ch, KICK_CLOSE_RETRIES); // close 失败(isActive)重试
            });
            log.info("kick executed: user={} device={}", kick.getUserId(), kick.getDeviceId());
        } catch (Exception e) {
            log.error("handle kick error: {}", message, e);
        }
    }

    /** close 重试次数(首次之外重试几次;仍失败靠心跳兜底) */
    private static final int KICK_CLOSE_RETRIES = 1;

    /**
     * 关闭目标连接;close 是异步的,回调里用 {@code isActive()} 判断是否真关掉,
     * 仍活着则重试(重试仍在该连接 EventLoop 上执行,与 I/O 不交错)。
     * 重试耗尽仍 active → 告警,交给心跳兜底(路由表已删 → 续期前 getNode=null → 关连接)。
     */
    private void closeWithRetry(Channel ch, int retriesLeft) {
        ch.close().addListener((ChannelFutureListener) future -> {
            if (ch.isActive()) {
                if (retriesLeft > 0) {
                    log.warn("kick close: channel still active, retry ({} left): {}", retriesLeft, ch);
                    ch.eventLoop().execute(() -> closeWithRetry(ch, retriesLeft - 1));
                } else {
                    log.error("kick close FAILED after retries, rely on heartbeat (session already removed): {}",
                            ch);
                }
            }
        });
    }

    public void shutdown() {
        if (connection != null) connection.close();
        if (redisClient != null) redisClient.shutdown();
    }
}
