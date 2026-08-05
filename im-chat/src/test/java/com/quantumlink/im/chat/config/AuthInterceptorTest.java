package com.quantumlink.im.chat.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        // writeUnauthorized 里调用 response.getWriter().write(...)
        try {
            when(response.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        interceptor = new AuthInterceptor(redisTemplate);
    }

    @Test
    void preHandle_options_pass() throws Exception {
        when(request.getMethod()).thenReturn("OPTIONS");
        assertTrue(interceptor.preHandle(request, response, null));
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void preHandle_missingToken_rejected() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Authorization")).thenReturn(null);
        assertFalse(interceptor.preHandle(request, response, null));
        verify(response).setStatus(401);
    }

    @Test
    void preHandle_invalidToken_rejected() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Authorization")).thenReturn("Bearer badtoken");
        when(valueOps.get("im:token:badtoken")).thenReturn(null);
        assertFalse(interceptor.preHandle(request, response, null));
        verify(response).setStatus(401);
    }

    @Test
    void preHandle_validToken_setsAttribute() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Authorization")).thenReturn("Bearer goodtoken");
        when(valueOps.get("im:token:goodtoken")).thenReturn("u_1");
        assertTrue(interceptor.preHandle(request, response, null));
        verify(request).setAttribute(AuthInterceptor.ATTR_USER_ID, "u_1");
    }

    @Test
    void preHandle_wrongScheme_rejected() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Authorization")).thenReturn("Basic abc");
        assertFalse(interceptor.preHandle(request, response, null));
    }
}
