package com.quantumlink.im.common.util;

import org.junit.jupiter.api.Test;

import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/** CRC 工具全覆盖。 */
class CrcUtilTest {

    @Test
    void crc32_knownValue() {
        byte[] header = new byte[10];
        byte[] body = "hello".getBytes();
        CRC32 expected = new CRC32();
        expected.update(header, 0, header.length);
        expected.update(body, 0, body.length);
        assertEquals((int) expected.getValue(), CrcUtil.crc32(header, body));
    }

    @Test
    void crc32_emptyBody() {
        byte[] header = new byte[10];
        CRC32 expected = new CRC32();
        expected.update(header, 0, header.length);
        assertEquals((int) expected.getValue(), CrcUtil.crc32(header, null));
        assertEquals((int) expected.getValue(), CrcUtil.crc32(header, new byte[0]));
    }

    @Test
    void crc32_differentBody_differentCrc() {
        byte[] header = new byte[10];
        assertNotEquals(CrcUtil.crc32(header, "a".getBytes()),
                CrcUtil.crc32(header, "b".getBytes()));
    }
}
