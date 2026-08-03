package com.quantumlink.im.common.util;

import java.util.zip.CRC32;

/**
 * CRC32 校验工具。
 *
 * <p>用于帧尾 CRC:对 header(10B)+ body 计算,附加在帧尾。TCP 本身有 checksum,
 * 但只覆盖单段传输;CRC32 提供端到端完整性校验,防中间层(代理/网关)改写。
 */
public final class CrcUtil {
    private CrcUtil() {}

    /**
     * 计算 header + body 的 CRC32。
     *
     * @param header 帧头(不含 CRC),长度 = HEADER_LENGTH
     * @param body   帧体,可为空
     * @return 4 字节 CRC 值
     */
    public static int crc32(byte[] header, byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(header, 0, header.length);
        if (body != null && body.length > 0) {
            crc.update(body, 0, body.length);
        }
        return (int) crc.getValue();
    }
}
