package com.quantumlink.im.connect.handler;

import com.quantumlink.im.common.protocol.*;
import com.quantumlink.im.common.util.ProtocolUtil;
import com.quantumlink.im.connect.service.ChannelManager;
import com.quantumlink.im.connect.service.ConnectionContext;
import com.quantumlink.im.connect.service.SessionRegistry;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * 握手处理器:连接建立后的第一帧必须是 HANDSHAKE。
 *
 * <p>流程(异步鉴权版):
 * <ol>
 *   <li>收到 HANDSHAKE,解析 token + deviceId;</li>
 *   <li><b>异步</b>调 {@code SessionRegistry.authenticateAsync} 查 Redis,不阻塞 EventLoop;</li>
 *   <li>鉴权期间到达的后续业务帧(MSG/DELIVER_ACK/READ_ACK)入 <b>per-connection 待鉴权队列</b>
 *       暂存——有界(超限关连接,防恶意灌帧)+ 超时(3s 鉴权未回 → 清队列关连接);</li>
 *   <li>鉴权完成 → 回投该连接 EventLoop:通过则注册会话、绑定上下文、回 HANDSHAKE_ACK,
 *       再按到达顺序补处理暂存帧(不丢不乱序);失败则清队列 + 关连接。</li>
 * </ol>
 *
 * <p>为什么异步:握手 authenticate 是 Redis GET,同步写在 EventLoop 上,一次 Redis 抖动
 * 会卡住该 EventLoop 上所有连接(线程雪崩)。异步后 EventLoop 只负责提交与回投,
 * Redis 往返交给 Lettuce IO 线程——"EventLoop 只做收发"原则的补全。
 *
 * <p>为什么用待鉴权队列:Netty channelRead 是连续的,鉴权异步期间客户端可能已发来
 * 业务帧;直接丢弃会丢消息,不处理会积压。先入队暂存,鉴权成功后按序补处理,
 * 失败则随连接一起清掉——"先鉴权后收发"从阻塞式变成事件驱动。
 */
public class HandshakeHandler extends SimpleChannelInboundHandler<ImFrame> {
    private static final Logger log = LoggerFactory.getLogger(HandshakeHandler.class);

    /** 鉴权超时(秒):超时未回 → 清队列 + 关连接(Redis 挂/慢时不能死等) */
    private static final long AUTH_TIMEOUT_SECONDS = 3;
    /** 待鉴权队列上限:超限 = 恶意灌帧,直接关(待鉴权限流) */
    private static final int MAX_PENDING_FRAMES = 64;

    private final SessionRegistry sessionRegistry;
    private final String nodeId;

    /** 已提交鉴权(第一帧 HANDSHAKE 已受理;重复 HANDSHAKE 视为非法) */
    private boolean authSubmitted;
    /** 鉴权期间暂存的后续业务帧(按到达顺序补处理) */
    private final ArrayDeque<ImFrame> pendingFrames = new ArrayDeque<>();
    /** 鉴权超时定时器 */
    private ScheduledFuture<?> authTimeout;

    public HandshakeHandler(SessionRegistry sessionRegistry, String nodeId) {
        this.sessionRegistry = sessionRegistry;
        this.nodeId = nodeId;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImFrame frame) {
        if (frame.getType() == FrameType.HANDSHAKE) {
            if (authSubmitted) {
                sendError(ctx, "BAD_HANDSHAKE", "duplicate handshake");
                ctx.close();
                return;
            }
            authSubmitted = true;
            startAuth(ctx, frame);
            return;
        }

        if (!authSubmitted) {
            // 第一帧不是 HANDSHAKE,直接拒绝
            sendError(ctx, "AUTH_REQUIRED", "first frame must be HANDSHAKE");
            ctx.close();
            return;
        }

        // 鉴权进行中:业务帧入队暂存,鉴权完成后按序补处理(不丢不乱序)
        if (pendingFrames.size() >= MAX_PENDING_FRAMES) {
            log.warn("too many frames before auth, close: remote={}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        pendingFrames.offer(frame);
    }

    /** 提交异步鉴权(不阻塞 EventLoop);超时兜底 + 回调回投 */
    private void startAuth(ChannelHandlerContext ctx, ImFrame frame) {
        HandshakePayload handshake = ProtocolUtil.parseBody(frame, HandshakePayload.class);
        if (handshake == null || handshake.getToken() == null || handshake.getDeviceId() == null) {
            sendError(ctx, "BAD_HANDSHAKE", "token and deviceId required");
            ctx.close();
            return;
        }

        // 鉴权超时兜底:Redis 挂/慢时不能死等,超时清队列 + 关连接
        this.authTimeout = ctx.executor().schedule(() -> {
            log.warn("auth timeout, close: remote={}", ctx.channel().remoteAddress());
            pendingFrames.clear();
            ctx.close();
        }, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 异步鉴权:Redis 往返在 Lettuce IO 线程,不阻塞当前 EventLoop
        sessionRegistry.authenticateAsync(handshake.getToken()).whenComplete((userId, err) -> {
            // 回调线程不能碰 channel,回投到该连接的 EventLoop 串行执行
            ctx.channel().eventLoop().execute(() -> {
                if (this.authTimeout != null) {
                    this.authTimeout.cancel(false);
                }
                if (err != null || userId == null || !ctx.channel().isActive()) {
                    log.warn("handshake rejected: token={} {}", handshake.getToken(),
                            err == null ? "invalid token" : ("err=" + err.getMessage()));
                    pendingFrames.clear();
                    if (ctx.channel().isActive()) {
                        sendError(ctx, "AUTH_FAILED", "invalid token");
                        ctx.close();
                    }
                    return;
                }
                completeAuth(ctx, handshake, userId);
            });
        });
    }

    /** 鉴权通过:注册会话 + 绑定上下文 + 回 HANDSHAKE_ACK + 补处理暂存帧 + 移除本 handler */
    private void completeAuth(ChannelHandlerContext ctx, HandshakePayload handshake, String userId) {
        // 鉴权通过:注册会话(Redis 路由表)+ 加入本地连接管理 + 绑定上下文
        sessionRegistry.register(userId, handshake.getDeviceId(), nodeId);
        ChannelManager.add(userId, handshake.getDeviceId(), ctx.channel());
        ConnectionContext.bind(ctx.channel(), userId, handshake.getDeviceId());

        HandshakeAckPayload ack = new HandshakeAckPayload();
        ack.setSuccess(true);
        ack.setUserId(userId);
        // 用 channel.writeAndFlush(从 tail 出站,经过 ImFrameEncoder),不能用 ctx.writeAndFlush
        // (ctx.write 从当前 handler 向 head 出站,会绕过它后面的 encoder)
        ctx.channel().writeAndFlush(ProtocolUtil.buildFrame(FrameType.HANDSHAKE_ACK, ack));

        // 按到达顺序补处理鉴权期间暂存的业务帧(此时已 authenticated,MessageHandler 可处理)
        ImFrame f;
        while ((f = pendingFrames.poll()) != null) {
            ctx.fireChannelRead(f);
        }

        // 握手完成,移除此 handler,后续帧交 MessageHandler
        ctx.pipeline().remove(this);
        log.info("handshake ok: user={} device={}", userId, handshake.getDeviceId());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // 被移除/连接关闭时兜底:取消超时定时器、清空暂存帧(防泄漏)
        if (authTimeout != null) {
            authTimeout.cancel(false);
            authTimeout = null;
        }
        pendingFrames.clear();
    }

    private void sendError(ChannelHandlerContext ctx, String code, String message) {
        ErrorPayload error = new ErrorPayload();
        error.setCode(code);
        error.setMessage(message);
        ctx.channel().writeAndFlush(ProtocolUtil.buildFrame(FrameType.ERROR, error))
                .addListener(ChannelFutureListener.CLOSE);
    }
}
