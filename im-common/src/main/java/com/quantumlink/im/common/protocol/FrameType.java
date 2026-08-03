package com.quantumlink.im.common.protocol;

/**
 * TCP 帧类型。
 */
public enum FrameType {
    /** 握手 */
    HANDSHAKE((byte) 1),
    /** 握手应答 */
    HANDSHAKE_ACK((byte) 2),
    /** 消息 */
    MSG((byte) 3),
    /** 消息回执 */
    MSG_ACK((byte) 4),
    /** 心跳 */
    PING((byte) 5),
    /** 心跳应答 */
    PONG((byte) 6),
    /** 错误 */
    ERROR((byte) 7);

    private final byte code;

    FrameType(byte code) { this.code = code; }

    public byte code() { return code; }

    public static FrameType fromCode(byte code) {
        for (FrameType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("unknown frame type: " + code);
    }
}
