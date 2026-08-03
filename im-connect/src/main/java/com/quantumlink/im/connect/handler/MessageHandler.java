package com.quantumlink.im.connect.handler;

import com.quantumlink.im.common.protocol.FrameType;
import com.quantumlink.im.common.protocol.ImFrame;
import com.quantumlink.im.common.protocol.MessagePayload;
import com.quantumlink.im.common.util.ProtocolUtil;
import com.quantumlink.im.connect.service.ConnectionContext;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;

/**
 * 消息处理器:处理握手后的业务帧(MSG / PING / PONG)。
 *
 * <p>核心设计(面试点):<b>EventLoop 只做收发,阻塞操作丢业务线程池。</b>
 * <ul>
 *   <li>channelRead0 在 EventLoop 线程执行,只做最轻的帧类型分发;</li>
 *   <li>MSG 的 JSON 反序列化 + 发 RocketMQ(可能阻塞)交给 {@code bizExecutor};</li>
 *   <li>写回 Channel 时用 {@code channel.eventLoop().execute(...)} 保证线程安全。</li>
 * </ul>
 *
 * <p>为什么不能阻塞 EventLoop:一个 EventLoop 管着成百上千条连接,
 * 在其中同步 JSON + 发 MQ,一条慢就把该 EventLoop 上所有连接全部拖垮(线程雪崩)。
 */
public class MessageHandler extends SimpleChannelInboundHandler<ImFrame> {
    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final ExecutorService bizExecutor;
    private final MessageDispatcher dispatcher;

    public MessageHandler(ExecutorService bizExecutor, MessageDispatcher dispatcher) {
        this.bizExecutor = bizExecutor;
        this.dispatcher = dispatcher;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImFrame frame) {
        if (!ConnectionContext.authenticated(ctx.channel())) {
            ctx.close();
            return;
        }

        switch (frame.getType()) {
            case MSG -> handleMessage(ctx.channel(), frame);
            case PING -> handlePing(ctx.channel());
            case PONG -> handlePong(ctx.channel());
            default -> log.warn("unexpected frame type after handshake: {}", frame.getType());
        }
    }

    /** 消息帧:EventLoop 只做轻量分发,重活交给业务线程池 */
    private void handleMessage(Channel channel, ImFrame frame) {
        // 轻量解析出 userId/deviceId 快照(不阻塞)
        String userId = ConnectionContext.userId(channel);
        String deviceId = ConnectionContext.deviceId(channel);

        // 丢业务线程池:反序列化 + 发 RocketMQ 在这里做,不阻塞 EventLoop
        bizExecutor.submit(() -> {
            try {
                MessagePayload payload = ProtocolUtil.parseBody(frame, MessagePayload.class);
                if (payload == null) {
                    log.warn("parse message failed: user={}", userId);
                    return;
                }
                dispatcher.dispatchUpstream(userId, deviceId, payload);
            } catch (Exception e) {
                log.error("handle message error: user={}", userId, e);
            }
        });
    }

    private void handlePing(Channel channel) {
        // 心跳由专门 handler 处理,这里兜底回 PONG
        channel.writeAndFlush(ProtocolUtil.buildFrame(FrameType.PONG, new byte[0]));
    }

    private void handlePong(Channel channel) {
        // 客户端心跳应答,重置空闲计数由 IdleStateHandler 自动处理
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 断连清理:移除本地 channel + 清 Redis 会话
        dispatcher.onDisconnect(ctx.channel());
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("channel error: remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
