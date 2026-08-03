package com.quantumlink.im.common.util;

import com.quantumlink.im.common.protocol.FrameType;
import com.quantumlink.im.common.protocol.ImFrame;

/**
 * 协议工具:帧构建、编解码。
 * MVP 占位,Phase 1 实现完整编解码器。
 */
public final class ProtocolUtil {
    private ProtocolUtil() {}

    public static ImFrame buildFrame(FrameType type, byte[] body) {
        return new ImFrame(type, body);
    }
}
