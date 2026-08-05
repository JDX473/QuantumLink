package com.quantumlink.im.connect.service;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 连接上下文全覆盖:bind/userId/deviceId/authenticated/clear。 */
class ConnectionContextTest {

    @Test
    void bind_thenRead() {
        EmbeddedChannel ch = new EmbeddedChannel();
        ConnectionContext.bind(ch, "u1", "d1");
        assertEquals("u1", ConnectionContext.userId(ch));
        assertEquals("d1", ConnectionContext.deviceId(ch));
        assertTrue(ConnectionContext.authenticated(ch));
    }

    @Test
    void unbound_defaults() {
        EmbeddedChannel ch = new EmbeddedChannel();
        assertNull(ConnectionContext.userId(ch));
        assertNull(ConnectionContext.deviceId(ch));
        assertFalse(ConnectionContext.authenticated(ch));
    }

    @Test
    void clear_resets() {
        EmbeddedChannel ch = new EmbeddedChannel();
        ConnectionContext.bind(ch, "u1", "d1");
        ConnectionContext.clear(ch);
        assertNull(ConnectionContext.userId(ch));
        assertNull(ConnectionContext.deviceId(ch));
        assertFalse(ConnectionContext.authenticated(ch));
    }

    @Test
    void rebind_overwrites() {
        EmbeddedChannel ch = new EmbeddedChannel();
        ConnectionContext.bind(ch, "u1", "d1");
        ConnectionContext.bind(ch, "u2", "d2");
        assertEquals("u2", ConnectionContext.userId(ch));
        assertEquals("d2", ConnectionContext.deviceId(ch));
    }

    @Test
    void bindWithNullUserId() {
        EmbeddedChannel ch = new EmbeddedChannel();
        ConnectionContext.bind(ch, null, "d1");
        assertNull(ConnectionContext.userId(ch));
        assertEquals("d1", ConnectionContext.deviceId(ch));
        assertTrue(ConnectionContext.authenticated(ch));
    }
}
