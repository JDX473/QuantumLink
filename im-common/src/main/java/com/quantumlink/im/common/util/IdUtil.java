package com.quantumlink.im.common.util;

import java.util.UUID;

/**
 * ID 工具。client_msg_id 由客户端生成 UUID(crypto.randomUUID,见 clients/client-core);
 * userId/deviceId 由服务端分配。此处提供通用 uuid()(无横线,16 字节)。
 * MVP 占位。
 */
public final class IdUtil {
    private IdUtil() {}

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
