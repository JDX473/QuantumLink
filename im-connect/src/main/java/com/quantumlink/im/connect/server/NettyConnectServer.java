package com.quantumlink.im.connect.server;

import com.quantumlink.im.common.protocol.ImFrameDecoder;
import com.quantumlink.im.common.protocol.ImFrameEncoder;
import com.quantumlink.im.connect.config.ConnectConfig;
import com.quantumlink.im.connect.consumer.DownstreamConsumer;
import com.quantumlink.im.connect.handler.HandshakeHandler;
import com.quantumlink.im.connect.handler.HeartbeatHandler;
import com.quantumlink.im.connect.handler.MessageDispatcher;
import com.quantumlink.im.connect.handler.MessageHandler;
import com.quantumlink.im.connect.handler.UpstreamProducer;
import com.quantumlink.im.connect.service.NodeReporter;
import com.quantumlink.im.connect.service.KickSubscriber;
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
    private DownstreamConsumer downstreamConsumer;
    private DownstreamConsumer signalConsumer;
    private NodeReporter nodeReporter;
    private KickSubscriber kickSubscriber;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public NettyConnectServer(ConnectConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
        // 1. 依赖
        String nodeId = nodeId();
        this.sessionRegistry = new SessionRegistry(config);
        this.upstreamProducer = new UpstreamProducer(config);
        // 下行分两个通道:server2client(消息)+ server2signal(信令)——信令不占消息队列,
        // 信令积压/重试不影响消息投递(队列级隔离)
        this.downstreamConsumer = new DownstreamConsumer(config, nodeId, "server2client", "msg");
        this.signalConsumer = new DownstreamConsumer(config, nodeId, "server2signal", "signal");
        this.nodeReporter = new NodeReporter(config, nodeId);
        this.nodeReporter.start();
        // 踢人订阅者:订阅 Redis im:kick,收到指令关本地目标连接(多端踢设备)
        this.kickSubscriber = new KickSubscriber(config);

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
                                .addLast("message", new MessageHandler(dispatcher))
                                .addLast("frameEncoder", new ImFrameEncoder());
                    }
                });

        ChannelFuture future = bootstrap.bind(config.port).sync();
        log.info("im-connect listening on port {} (worker threads={})",
                config.port, config.workerThreads);
        future.channel().closeFuture().sync();
    }

    /**
     * 节点 ID:水平扩展的节点标识。
     *
     * <p>用 {@code host:port} 作为节点唯一标识(不同端口天然不同节点)。
     * 它是 Redis 会话表的值、MQ tag 的来源、调度接口返回的地址,三处必须一致。
     * 多节点:启动时用 {@code -Dim.connect.port=9998} 指定不同端口 → 不同节点。
     */
    private String nodeId() {
        return "127.0.0.1:" + config.port;
    }

    public void shutdown() {
        if (nodeReporter != null) nodeReporter.shutdown();
        if (upstreamProducer != null) upstreamProducer.shutdown();
        if (downstreamConsumer != null) downstreamConsumer.shutdown();
        if (signalConsumer != null) signalConsumer.shutdown();
        if (kickSubscriber != null) kickSubscriber.shutdown();
        if (sessionRegistry != null) sessionRegistry.shutdown();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
