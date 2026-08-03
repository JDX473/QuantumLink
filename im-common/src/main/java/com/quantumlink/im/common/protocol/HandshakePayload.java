package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 握手请求体(客户端 → 服务端第一帧)。
 *
 * <p>TCP 建连后必须先发 HANDSHAKE,携带 token + device_id;
 * 服务端查 Redis 校验 token,通过才回 HANDSHAKE_ACK。
 */
@Getter
@Setter
public class HandshakePayload {
    /** 登录 token(chat 发,connect 校验) */
    private String token;
    /** 服务端分配的设备 ID */
    private String deviceId;
}
