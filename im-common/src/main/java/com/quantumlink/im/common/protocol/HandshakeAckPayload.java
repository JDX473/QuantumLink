package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 握手应答体(服务端 → 客户端)。
 */
@Getter
@Setter
public class HandshakeAckPayload {
    /** 是否通过鉴权 */
    private boolean success;

    /** 通过鉴权后回填的 userId(客户端据此知道自己的身份) */
    private String userId;

    /** 失败原因(success=false 时) */
    private String reason;
}
