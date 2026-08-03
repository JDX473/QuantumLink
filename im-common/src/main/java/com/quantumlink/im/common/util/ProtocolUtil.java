package com.quantumlink.im.common.util;

import com.quantumlink.im.common.protocol.FrameType;
import com.quantumlink.im.common.protocol.ImFrame;

import java.nio.charset.StandardCharsets;

/**
 * 协议工具:帧构建、编解码。
 *
 * <p>线格式:{@code magic(4B) + version(1B) + type(1B) + bodyLen(4B) + body + crc32(4B)}
 * <p>编码器:{@link com.quantumlink.im.common.protocol.ImFrameEncoder}
 * <p>解码器:{@link com.quantumlink.im.common.protocol.ImFrameDecoder}
 */
public final class ProtocolUtil {
    private ProtocolUtil() {}

    /** 构建一帧(JSON body) */
    public static ImFrame buildFrame(FrameType type, Object body) {
        byte[] bytes = body == null ? new byte[0] : JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        return new ImFrame(type, bytes);
    }

    /** 构建一帧(原始字节 body) */
    public static ImFrame buildFrame(FrameType type, byte[] body) {
        return new ImFrame(type, body);
    }

    /** 帧 body 反序列化为指定类型 */
    public static <T> T parseBody(ImFrame frame, Class<T> clazz) {
        String json = frame.bodyAsString();
        if (json == null || json.isEmpty()) {
            return null;
        }
        return JsonUtil.fromJson(json, clazz);
    }
}
