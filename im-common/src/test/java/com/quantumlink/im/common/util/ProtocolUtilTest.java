package com.quantumlink.im.common.util;

import com.quantumlink.im.common.protocol.FrameType;
import com.quantumlink.im.common.protocol.HandshakePayload;
import com.quantumlink.im.common.protocol.ImFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 协议工具全覆盖。 */
class ProtocolUtilTest {

    @Test
    void buildFrame_fromObject() {
        HandshakePayload h = new HandshakePayload();
        h.setToken("t1");
        h.setDeviceId("d1");
        ImFrame frame = ProtocolUtil.buildFrame(FrameType.HANDSHAKE, h);
        assertEquals(FrameType.HANDSHAKE, frame.getType());
        assertNotNull(frame.getBody());
        // round trip
        HandshakePayload back = ProtocolUtil.parseBody(frame, HandshakePayload.class);
        assertEquals("t1", back.getToken());
        assertEquals("d1", back.getDeviceId());
    }

    @Test
    void buildFrame_fromBytes() {
        byte[] body = new byte[]{1, 2, 3};
        ImFrame frame = ProtocolUtil.buildFrame(FrameType.PING, body);
        assertEquals(FrameType.PING, frame.getType());
        assertArrayEquals(body, frame.getBody());
    }

    @Test
    void parseBody_invalidJson_throws() {
        ImFrame frame = ProtocolUtil.buildFrame(FrameType.MSG, new byte[]{0, 1});
        assertThrows(RuntimeException.class, () -> ProtocolUtil.parseBody(frame, HandshakePayload.class));
    }

    @Test
    void parseBody_nullBody_returnsNull() {
        ImFrame frame = new ImFrame(FrameType.MSG, null);
        assertNull(ProtocolUtil.parseBody(frame, HandshakePayload.class));
    }
}
