package com.quantumlink.im.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ID 工具全覆盖。 */
class IdUtilTest {

    @Test
    void uuid_lengthAndFormat() {
        String id = IdUtil.uuid();
        // UUID 去横线 = 32 位 hex
        assertEquals(32, id.length());
        assertTrue(id.matches("[0-9a-f]{32}"));
    }

    @Test
    void uuid_uniqueAcrossCalls() {
        assertNotEquals(IdUtil.uuid(), IdUtil.uuid());
    }
}
