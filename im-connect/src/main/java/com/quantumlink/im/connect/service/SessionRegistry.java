package com.quantumlink.im.connect.service;

import com.quantumlink.im.connect.config.ConnectConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Redis 会话注册表。
 *
 * <p>职责:维护"每个设备 → 连接所在节点"的映射,供握手鉴权、心跳续期、
 * 以及(后续集群阶段)消息路由定位目标节点。
 *
 * <p>Key 设计:{@code im:session:{userId}:{deviceId}} → 节点 ID
 * <ul>
 *   <li>每设备一个 key(MVP 虽单节点,但 key 带设备维度,为多端/多节点留扩展)</li>
 *   <li>TTL = 30s,心跳续期 —— 断连但没清理时,TTL 兜底过期,防止"幽灵连接"</li>
 * </ul>
 *
 * <p>为什么用 Lettuce:基于 Netty 单连接多路复用,无连接池,契合 connect 非阻塞模型。
 */
public class SessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private static final String SESSION_PREFIX = "im:session:";
    private static final String DEVICES_PREFIX = "im:devices:";
    private static final long TTL_SECONDS = 30;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisAsyncCommands<String, String> asyncCommands;

    public SessionRegistry(ConnectConfig config) {
        RedisURI uri = RedisURI.builder()
                .withHost(config.redisHost)
                .withPort(config.redisPort)
                .withTimeout(Duration.ofSeconds(3))
                .build();
        this.redisClient = RedisClient.create(uri);
        this.connection = redisClient.connect();
        this.commands = connection.sync();
        this.asyncCommands = connection.async();
    }

    /** 会话 key */
    private String key(String userId, String deviceId) {
        return SESSION_PREFIX + userId + ":" + deviceId;
    }

    /** 设备列表 key:im:devices:{userId} → Set<deviceId>(查在线用,O(1),替代 keys 扫描) */
    private String devicesKey(String userId) {
        return DEVICES_PREFIX + userId;
    }

    /** 注册会话(握手通过后调用):写会话 key + 加入设备 Set */
    public void register(String userId, String deviceId, String nodeId) {
        commands.setex(key(userId, deviceId), TTL_SECONDS, nodeId);
        commands.sadd(devicesKey(userId), deviceId);
        log.info("session registered: user={} device={} node={}", userId, deviceId, nodeId);
    }

    /** 心跳续期(每 10s 心跳刷新 TTL) */
    public void refresh(String userId, String deviceId) {
        commands.expire(key(userId, deviceId), TTL_SECONDS);
    }

    /** 清理会话(断连时调用):删会话 key + 移出设备 Set */
    public void remove(String userId, String deviceId) {
        commands.del(key(userId, deviceId));
        commands.srem(devicesKey(userId), deviceId);
    }

    /** 查询用户某设备的节点 */
    public String getNode(String userId, String deviceId) {
        return commands.get(key(userId, deviceId));
    }

    /** 查询用户所有在线设备的 nodeId(替代 keys 扫描:SMEMBERS O(1) + 逐个 GET) */
    public java.util.List<String> getOnlineNodes(String userId) {
        java.util.List<String> result = new java.util.ArrayList<>();
        java.util.Set<String> devices = commands.smembers(devicesKey(userId));
        for (String deviceId : devices) {
            String nodeId = commands.get(key(userId, deviceId));
            if (nodeId != null) {
                result.add(nodeId);
            }
        }
        return result;
    }

    /** 握手鉴权:校验 token 是否有效,返回 userId(null=无效) */
    public String authenticate(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return commands.get("im:token:" + token);
    }

    /**
     * 异步握手鉴权:校验 token 返回 userId,<b>不阻塞调用线程</b>。
     * Redis 往返在 Lettuce 的 IO 线程完成,回调由调用方决定在哪执行
     * (HandshakeHandler 会回投到该连接的 EventLoop)。配合待鉴权帧队列,
     * 让握手不再阻塞 EventLoop——Redis 抖动不会卡住同 EventLoop 上的所有连接。
     */
    public CompletableFuture<String> authenticateAsync(String token) {
        if (token == null || token.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return asyncCommands.get("im:token:" + token).toCompletableFuture();
    }

    public void shutdown() {
        connection.close();
        redisClient.shutdown();
    }
}
