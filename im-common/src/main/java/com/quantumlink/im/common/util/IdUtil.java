package com.quantumlink.im.common.util;

import java.util.UUID;

/**
 * ID 工具。msgId 由客户端生成(device_id+自增),此处提供 device_id 生成。
 * MVP 占位。
 */
public final class IdUtil {
    private IdUtil() {}

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
