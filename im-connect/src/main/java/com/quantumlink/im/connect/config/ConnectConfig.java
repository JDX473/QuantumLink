package com.quantumlink.im.connect.config;

/**
 * im-connect 配置。从环境变量 / 启动参数读取,零 Spring 依赖。
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

    /** RocketMQ */
    public String namesrvAddr = "127.0.0.1:9876";
    /** 上行 topic(客户端 → chat) */
    public String upstreamTopic = "client2server";

    public static ConnectConfig fromEnv() {
        ConnectConfig c = new ConnectConfig();
        c.port = Integer.parseInt(System.getProperty("im.connect.port", "9999"));
        c.namesrvAddr = System.getProperty("im.connect.namesrv", "127.0.0.1:9876");
        c.redisHost = System.getProperty("im.connect.redis.host", "127.0.0.1");
        c.redisPort = Integer.parseInt(System.getProperty("im.connect.redis.port", "6379"));
        if (System.getProperty("im.connect.biz.threads") != null) {
            c.bizThreads = Integer.parseInt(System.getProperty("im.connect.biz.threads"));
        }
        return c;
    }
}
