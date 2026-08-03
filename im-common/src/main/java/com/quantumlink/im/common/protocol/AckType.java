package com.quantumlink.im.common.protocol;

/**
 * ACK 回执类型(MSG_ACK 帧的语义)。
 */
public enum AckType {
    /** 已存储:chat 落库成功,消息安全入库(可靠锚点) */
    STORE,
    /** 对方已送达:接收方客户端已收到(Phase 2 双 ACK 的第二跳) */
    DELIVER
}
