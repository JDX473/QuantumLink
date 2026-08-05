package com.quantumlink.im.chat.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthContextTest {

    @Test
    void currentUserId_readsAttribute() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn("u_1");
        assertEquals("u_1", AuthContext.currentUserId(req));
    }

    @Test
    void currentUserId_nullAttribute() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(null);
        assertNull(AuthContext.currentUserId(req));
    }

    @Test
    void isConversationParticipant_firstPart() {
        assertTrue(AuthContext.isConversationParticipant("u1#u2", "u1"));
    }

    @Test
    void isConversationParticipant_secondPart() {
        assertTrue(AuthContext.isConversationParticipant("u1#u2", "u2"));
    }

    @Test
    void isConversationParticipant_notMember() {
        assertFalse(AuthContext.isConversationParticipant("u1#u2", "u3"));
    }

    @Test
    void isConversationParticipant_badFormat() {
        assertFalse(AuthContext.isConversationParticipant("no-hash", "u1"));
    }

    @Test
    void isConversationParticipant_nullInputs() {
        assertFalse(AuthContext.isConversationParticipant(null, "u1"));
        assertFalse(AuthContext.isConversationParticipant("u1#u2", null));
    }
}
