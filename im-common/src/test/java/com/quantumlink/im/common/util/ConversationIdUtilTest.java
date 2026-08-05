package com.quantumlink.im.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 会话 ID 工具全覆盖。 */
class ConversationIdUtilTest {

    @Test
    void build_aLessThanB() {
        assertEquals("a#b", ConversationIdUtil.build("a", "b"));
    }

    @Test
    void build_bLessThanA() {
        assertEquals("a#b", ConversationIdUtil.build("b", "a"));
    }

    @Test
    void build_equal() {
        assertEquals("a#a", ConversationIdUtil.build("a", "a"));
    }

    @Test
    void build_nullFirst() {
        assertEquals("null#b", ConversationIdUtil.build(null, "b"));
    }

    @Test
    void build_nullSecond() {
        assertEquals("a#null", ConversationIdUtil.build("a", null));
    }

    @Test
    void build_bothNull() {
        assertEquals("null#null", ConversationIdUtil.build(null, null));
    }

    @Test
    void build_samePair_stableRegardlessOfOrder() {
        assertEquals(ConversationIdUtil.build("user1", "user2"),
                ConversationIdUtil.build("user2", "user1"));
    }
}
