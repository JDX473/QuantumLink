package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.config.AuthInterceptor;
import com.quantumlink.im.chat.dto.MessagePageDto;
import com.quantumlink.im.chat.service.MessageQueryService;
import com.quantumlink.im.chat.service.ReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageControllerTest {

    private MessageQueryService service;
    private ReadService readService;
    private HttpServletRequest request;
    private MessageController controller;

    @BeforeEach
    void setUp() {
        service = mock(MessageQueryService.class);
        readService = mock(ReadService.class);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn("u_me");
        controller = new MessageController(service, readService);
    }

    @Test
    void pullMessages_participant_returns() {
        when(service.pullMessages("u_me#u_other", 5, 10))
                .thenReturn(new MessagePageDto());
        Object result = controller.pullMessages("u_me#u_other", 5, 10, request);
        assertNotNull(result);
        verify(service).pullMessages("u_me#u_other", 5, 10);
    }

    @Test
    void pullMessages_setsPeerReadSeq() {
        when(service.pullMessages("u_me#u_other", 5, 10)).thenReturn(new MessagePageDto());
        when(readService.peerReadSeq("u_me#u_other", "u_me")).thenReturn(7L);
        MessagePageDto dto = (MessagePageDto) controller.pullMessages("u_me#u_other", 5, 10, request);
        assertEquals(7L, dto.getPeerReadSeq());
        verify(readService).peerReadSeq("u_me#u_other", "u_me");
    }

    @Test
    void pullMessages_notParticipant_forbidden() {
        Object result = controller.pullMessages("u_a#u_b", 5, 10, request);
        assertTrue(result instanceof java.util.Map);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) result;
        assertEquals(false, m.get("success"));
        verify(service, never()).pullMessages(anyString(), anyLong(), any());
        verify(readService, never()).peerReadSeq(anyString(), anyString());
    }

    @Test
    void listConversations_usesContextUser() {
        controller.listConversations(request);
        verify(service).listConversations("u_me");
    }
}
