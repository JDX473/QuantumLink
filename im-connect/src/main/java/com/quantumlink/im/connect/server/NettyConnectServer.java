package com.quantumlink.im.connect.server;

import com.quantumlink.im.common.protocol.ImFrameDecoder;
import com.quantumlink.im.common.protocol.ImFrameEncoder;
import com.quantumlink.im.connect.config.ConnectConfig;
import com.quantumlink.im.connect.handler.HandshakeHandler;
import com.quantumlink.im.connect.handler.HeartbeatHandler;
import com.quantumlink.im.connect.handler.MessageDispatcher;
import com.quantumlink.im.connect.handler.MessageHandler;
import com.quantumlink.im.connect.handler.UpstreamProducer;
import com.quantumlink.im.connect.service.SessionRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * QuantumLink 长连接层 Netty TCP 服务器。
 *
 * <p>Pipeline(从前往后):
 * <pre>
 * ImFrameDecoder(粘包拆包+CRC)
 *   → HeartbeatHandler(心跳,收到 PING 续期 Redis + 回 PONG)
 *   → IdleStateHandler(30s 读空闲兜底)
 *   → HandshakeHandler(先鉴权后收发)
 *   → MessageHandler(EventLoop 只收发,阻塞丢业务线程池)
 * ImFrameEncoder(出站编码)
 * </pre>
 *
 * <p>关键设计:
 * <ul>
 *   <li>业务线程池隔离:EventLoop 不阻塞,JSON/MQ 在 biz pool 做;</li>
 *   <li>Epoll/Nio 自动选择:Windows 用 Nio(无 Epoll),Linux 用 Epoll(压测报告标注平台差异)。</li>
 * </ul>
 */
public class NettyConnectServer {
    private static final Logger log = LoggerFactory.getLogger(NettyConnectServer.class);

    private final ConnectConfig config;
    private SessionRegistry sessionRegistry;
    private UpstreamProducer upstreamProducer;
    private ExecutorService bizExecutor;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public NettyConnectServer(ConnectConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
        // 1. 依赖
        this.sessionRegistry = new SessionRegistry(config);
        this.upstreamProducer = new UpstreamProducer(config);
        this.bizExecutor = new ThreadPoolExecutor(
                config.bizThreads, config.bizThreads,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10_000),
                new ThreadPoolExecutor.CallerRunsPolicy());

        MessageDispatcher dispatcher = new MessageDispatcher(sessionRegistry, upstreamProducer);

        // 2. Netty
        bossGroup = new NioEventLoopGroup(config.bossThreads);
        workerGroup = new NioEventLoopGroup(config.workerThreads);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("frameDecoder", new ImFrameDecoder())
                                .addLast("heartbeat", new HeartbeatHandler(sessionRegistry))
                                .addLast("idle", new IdleStateHandler(30, 0, 0))
                                .addLast("handshake", new HandshakeHandler(sessionRegistry, nodeId()))
                                .addLast("message", new MessageHandler(bizExecutor, dispatcher))
                                .addLast("frameEncoder", new ImFrameEncoder());
                    }
                });

        ChannelFuture future = bootstrap.bind(config.port).sync();
        log.info("im-connect listening on port {} (biz threads={}, worker threads={})",
                config.port, config.bizThreads, config.workerThreads);
        future.channel().closeFuture().sync();
    }

    /** 节点 ID:MVP 单节点用 host:port,集群阶段由注册中心分配 */
    private String nodeId() {
        return "127.0.0.1:" + config.port;
    }

    public void shutdown() {
        if (bizExecutor != null) bizExecutor.shutdown();
        if (upstreamProducer != null) upstreamProducer.shutdown();
        if (sessionRegistry != null) sessionRegistry.shutdown();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
