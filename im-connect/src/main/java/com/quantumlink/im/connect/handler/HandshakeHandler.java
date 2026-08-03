package com.quantumlink.im.connect.handler;

import com.quantumlink.im.common.protocol.*;
import com.quantumlink.im.common.util.JsonUtil;
import com.quantumlink.im.common.util.ProtocolUtil;
import com.quantumlink.im.connect.service.ChannelManager;
import com.quantumlink.im.connect.service.ConnectionContext;
import com.quantumlink.im.connect.service.SessionRegistry;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 握手处理器:连接建立后的第一帧必须是 HANDSHAKE。
 *
 * <p>流程:
 * <ol>
 *   <li>收到 HANDSHAKE,解析 token + deviceId;</li>
 *   <li>SessionRegistry.authenticate(token) 查 Redis → 得 userId;</li>
 *   <li>通过 → 注册会话、绑定上下文、回 HANDSHAKE_ACK;</li>
 *   <li>失败 → 回 ERROR 并关闭连接。</li>
 * </ol>
 *
 * <p>为什么要"先握手后收发":长连接是状态通道,必须先建立身份再处理业务,
 * 否则任何连接都能伪造 userId 收发消息。
 */
public class HandshakeHandler extends SimpleChannelInboundHandler<ImFrame> {
    private static final Logger log = LoggerFactory.getLogger(HandshakeHandler.class);

    private final SessionRegistry sessionRegistry;
    private final String nodeId;

    public HandshakeHandler(SessionRegistry sessionRegistry, String nodeId) {
        this.sessionRegistry = sessionRegistry;
        this.nodeId = nodeId;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImFrame frame) {
        if (frame.getType() != FrameType.HANDSHAKE) {
            // 未握手就来别的帧,直接拒绝
            sendError(ctx, "AUTH_REQUIRED", "first frame must be HANDSHAKE");
            ctx.close();
            return;
        }

        HandshakePayload handshake = ProtocolUtil.parseBody(frame, HandshakePayload.class);
        if (handshake == null || handshake.getToken() == null || handshake.getDeviceId() == null) {
            sendError(ctx, "BAD_HANDSHAKE", "token and deviceId required");
            ctx.close();
            return;
        }

        String userId = sessionRegistry.authenticate(handshake.getToken());
        if (userId == null) {
            log.warn("handshake rejected: token={}", handshake.getToken());
            sendError(ctx, "AUTH_FAILED", "invalid token");
            ctx.close();
            return;
        }

        // 鉴权通过:注册会话 + 加入本地连接管理 + 绑定上下文 + 回 ACK
        sessionRegistry.register(userId, handshake.getDeviceId(), nodeId);
        ChannelManager.add(userId, handshake.getDeviceId(), ctx.channel());
        ConnectionContext.bind(ctx.channel(), userId, handshake.getDeviceId());

        HandshakeAckPayload ack = new HandshakeAckPayload();
        ack.setSuccess(true);
        ack.setUserId(userId);
        // 用 channel.writeAndFlush(从 tail 出站,经过 ImFrameEncoder),不能用 ctx.writeAndFlush
        // (ctx.write 从当前 handler 向 head 出站,会绕过它后面的 encoder)
        ctx.channel().writeAndFlush(ProtocolUtil.buildFrame(FrameType.HANDSHAKE_ACK, ack));

        // 握手完成后,移除此 handler,交由 MessageHandler 处理后续帧
        ctx.pipeline().remove(this);
        log.info("handshake ok: user={} device={}", userId, handshake.getDeviceId());
    }

    private void sendError(ChannelHandlerContext ctx, String code, String message) {
        ErrorPayload error = new ErrorPayload();
        error.setCode(code);
        error.setMessage(message);
        ctx.channel().writeAndFlush(ProtocolUtil.buildFrame(FrameType.ERROR, error))
                .addListener(ChannelFutureListener.CLOSE);
    }
}
