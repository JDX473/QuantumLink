package com.quantumlink.im.connect.handler;

import com.quantumlink.im.common.protocol.FrameType;
import com.quantumlink.im.common.util.ProtocolUtil;
import com.quantumlink.im.connect.service.ConnectionContext;
import com.quantumlink.im.connect.service.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳处理器:客户端心跳为主,服务端兜底。
 *
 * <p>机制:
 * <ul>
 *   <li>客户端每 10s 发 PING,服务端回 PONG;</li>
 *   <li>收到 PING 时顺带刷新 Redis 会话 TTL(续期),证明"连接还活着";</li>
 *   <li>IdleStateHandler(30s 读空闲)兜底:30s 没收到任何数据 → 判定死连接 → 断开清理。</li>
 * </ul>
 *
 * <p>为什么客户端心跳 + 服务端兜底:客户端主动心跳能续期会话 TTL、感知更快;
 * 服务端读空闲兜底防止"假活"(对端崩溃但 TCP 未断开)拖住 Redis 会话。
 */
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final SessionRegistry sessionRegistry;

    public HeartbeatHandler(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 非 ImFrame(如握手前)不处理
        if (!(msg instanceof com.quantumlink.im.common.protocol.ImFrame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (frame.getType() == FrameType.PING) {
            // 客户端心跳:回 PONG + 续期会话 TTL
            String userId = ConnectionContext.userId(ctx.channel());
            String deviceId = ConnectionContext.deviceId(ctx.channel());
            if (userId != null) {
                sessionRegistry.refresh(userId, deviceId);
            }
            ctx.channel().writeAndFlush(ProtocolUtil.buildFrame(FrameType.PONG, new byte[0]));
            return;
        }

        // 非心跳帧,继续往后传
        ctx.fireChannelRead(frame);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) {
            // 服务端读空闲兜底:30s 无任何数据,判定死连接
            log.info("read idle, closing: remote={}", ctx.channel().remoteAddress());
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
