package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

import java.nio.charset.StandardCharsets;

/**
 * 一条 TCP 协议帧。
 *
 * <p>线格式:{@code magic(4B) + version(1B) + type(1B) + bodyLen(4B) + body + crc32(4B)}
 *
 * <p>frameType 见 {@link FrameType};body 为 JSON(UTF-8),MVP 后预留换 protobuf。
 */
@Getter
@Setter
public class ImFrame {
    private FrameType type;
    private byte[] body;

    public ImFrame(FrameType type, byte[] body) {
        this.type = type;
        this.body = body;
    }

    public String bodyAsString() {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "ImFrame{type=" + type + ", body=" + bodyAsString() + "}";
    }
}
