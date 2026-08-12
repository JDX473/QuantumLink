package com.quantumlink.im.connect.config;

import io.lettuce.core.RedisURI;

import java.time.Duration;

/**
 * im-connect 配置。从环境变量 / 启动参数读取,零 Spring 依赖。
 * 优先级:系统属性(-Dim.x) > 环境变量(IM_X) > 默认值。
 */
public class ConnectConfig {
    /** 监听端口 */
    public int port;
    /** boss 线程数 */
    public int bossThreads = 1;
    /** worker 线程数(默认 2×CPU) */
    public int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
    /** 业务线程池大小(默认 2×CPU) */
    public int bizThreads = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);

    /** Redis */
    public String redisHost = "127.0.0.1";
    public int redisPort = 6379;
    /** Redis 密码(云上常设;空 = 无密码) */
    public String redisPassword = "";

    /** Nacos(服务注册中心):节点注册到 im-connect 服务,动态发现 + 健康检查 */
    public String nacosAddr = "127.0.0.1:8850";

    /** RocketMQ */
    public String namesrvAddr = "127.0.0.1:9876";
    /** 上行 topic(客户端 → chat) */
    public String upstreamTopic = "client2server";

    public static ConnectConfig fromEnv() {
        ConnectConfig c = new ConnectConfig();
        c.port = Integer.parseInt(propOrEnv("im.connect.port", "IM_CONNECT_PORT", "9999"));
        c.namesrvAddr = propOrEnv("im.connect.namesrv", "IM_ROCKETMQ_NAMESRV", "127.0.0.1:9876");
        c.redisHost = propOrEnv("im.connect.redis.host", "IM_REDIS_HOST", "127.0.0.1");
        c.redisPort = Integer.parseInt(propOrEnv("im.connect.redis.port", "IM_REDIS_PORT", "6379"));
        c.redisPassword = propOrEnv("im.connect.redis.password", "IM_REDIS_PASSWORD", "");
        c.nacosAddr = propOrEnv("im.connect.nacos", "IM_NACOS_ADDR", "127.0.0.1:8850");
        String biz = propOrEnv("im.connect.biz.threads", "IM_CONNECT_BIZ_THREADS", null);
        if (biz != null) {
            c.bizThreads = Integer.parseInt(biz);
        }
        return c;
    }

    /** 优先系统属性(-Dim.x),其次环境变量(IM_X),最后默认值——云部署可用环境变量覆盖,本地不设则走默认 */
    private static String propOrEnv(String prop, String env, String def) {
        String v = System.getProperty(prop);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(env);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    /** 构造 Redis URI(含可选密码)。SessionRegistry/KickSubscriber/NodeReporter 共用 */
    public RedisURI redisUri() {
        RedisURI.Builder b = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withTimeout(Duration.ofSeconds(3));
        if (redisPassword != null && !redisPassword.isEmpty()) {
            b.withPassword(redisPassword);
        }
        return b.build();
    }
}
