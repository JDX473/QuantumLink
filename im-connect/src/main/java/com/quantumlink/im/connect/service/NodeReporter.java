package com.quantumlink.im.connect.service;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.quantumlink.im.connect.config.ConnectConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 节点状态上报:水平扩展的"实例发现 + 负载指标"出口。
 *
 * <p>三层职责分离(与 chat 调度接口配合):
 * <ul>
 *   <li><b>Nacos(实例存在性)</b>:本节点注册到 Nacos 服务 {@code im-connect},
 *       Nacos 客户端自动维护心跳与健康检查。"有哪些节点"由注册中心动态感知,
 *       加节点/节点宕机都不需要手动配置;</li>
 *   <li><b>Redis(负载指标)</b>:每 1s 把本地连接数 SETEX 到
 *       {@code im:node:conns:{nodeId}}(TTL 3s)。连接数是瞬时指标,弱一致心跳足够;
 *       TTL 兜底防止节点残留旧值。</li>
 * </ul>
 *
 * <p>chat 调度接口查 Nacos 拿在线实例清单 + 查 Redis 拿实时连接数 → 最少连接决策,
 * 返回客户端"该连谁"(客户端不感知节点列表)。
 */
public class NodeReporter {
    private static final Logger log = LoggerFactory.getLogger(NodeReporter.class);

    private static final String CONNECT_SERVICE = "im-connect";
    private static final String NODE_CONNS_PREFIX = "im:node:conns:";
    private static final long CONNS_TTL_SECONDS = 3;
    private static final long REPORT_INTERVAL_SECONDS = 1;

    private final ConnectConfig config;
    private final String nodeId;

    private NamingService namingService;
    private Instance registeredInstance;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisCommands<String, String> commands;
    private ScheduledExecutorService scheduler;

    public NodeReporter(ConnectConfig config, String nodeId) {
        this.config = config;
        this.nodeId = nodeId;
    }

    /** 注册 Nacos 实例 + 启动连接数心跳上报 */
    public void start() {
        registerToNacos();
        startConnCountReporter();
        log.info("node reporter started: nodeId={} nacos={} connsTTL={}s",
                nodeId, config.nacosAddr, CONNS_TTL_SECONDS);
    }

    private void registerToNacos() {
        try {
            Properties props = new Properties();
            props.put(PropertyKeyConst.SERVER_ADDR, config.nacosAddr);
            this.namingService = NamingFactory.createNamingService(props);

            String host = hostOf(nodeId);
            int port = portOf(nodeId);

            registeredInstance = new Instance();
            registeredInstance.setIp(host);
            registeredInstance.setPort(port);
            registeredInstance.setHealthy(true);
            namingService.registerInstance(CONNECT_SERVICE, registeredInstance);
            log.info("registered to nacos: service={} instance={}:{}", CONNECT_SERVICE, host, port);
        } catch (Exception e) {
            throw new IllegalStateException("register to nacos failed: " + nodeId, e);
        }
    }

    private void startConnCountReporter() {
        // Lettuce 单连接多路复用(仿 SessionRegistry,契合 connect 非阻塞模型)
        RedisURI uri = config.redisUri();
        this.redisClient = RedisClient.create(uri);
        this.redisConnection = redisClient.connect();
        this.commands = redisConnection.sync();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "node-conn-reporter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::reportConnCount, 1, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 上报本地连接数:SETEX im:node:conns:{nodeId} 3 <count> */
    private void reportConnCount() {
        try {
            int count = ChannelManager.size();
            commands.setex(NODE_CONNS_PREFIX + nodeId, CONNS_TTL_SECONDS, String.valueOf(count));
        } catch (Exception e) {
            log.error("report conn count failed: nodeId={}", nodeId, e);
        }
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (redisConnection != null) {
            redisConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        if (namingService != null && registeredInstance != null) {
            try {
                namingService.deregisterInstance(CONNECT_SERVICE, registeredInstance);
                log.info("deregistered from nacos: service={} nodeId={}", CONNECT_SERVICE, nodeId);
            } catch (Exception e) {
                log.warn("deregister from nacos failed: nodeId={}", nodeId, e);
            }
        }
    }

    /** "127.0.0.1:19001" → "127.0.0.1" */
    private static String hostOf(String nodeId) {
        return nodeId.substring(0, nodeId.lastIndexOf(':'));
    }

    /** "127.0.0.1:19001" → 19001 */
    private static int portOf(String nodeId) {
        return Integer.parseInt(nodeId.substring(nodeId.lastIndexOf(':') + 1));
    }
}
