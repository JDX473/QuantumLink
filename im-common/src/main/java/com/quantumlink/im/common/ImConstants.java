package com.quantumlink.im.common;

import com.quantumlink.im.common.protocol.FrameType;

/**
 * 统一协议常量。
 */
public final class ImConstants {
    private ImConstants() {}

    /** 帧头魔数 "QNLC" */
    public static final int MAGIC = 0x514E4C43;

    /** 协议版本 */
    public static final byte VERSION = 1;

    /** 帧头固定长度:magic(4)+version(1)+type(1)+bodyLen(4) */
    public static final int HEADER_LENGTH = 10;

    /** 帧尾 CRC32 长度 */
    public static final int CRC_LENGTH = 4;

    /** 单帧 body 最大长度(防内存攻击) */
    public static final int MAX_BODY_LENGTH = 8 * 1024 * 1024;

    /** 服务端读空闲兜底(心跳 10s 的 3 倍) */
    public static final int IDLE_SECONDS = 30;

    /** Redis 会话 TTL(3×心跳间隔) */
    public static final long SESSION_TTL_SECONDS = 30;

    /** 消息重发超时(ms) */
    public static final long RESEND_TIMEOUT_MS = 3000;
}
