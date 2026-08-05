package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.dto.AuthDtos;
import com.quantumlink.im.chat.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private AuthService authService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        controller = new AuthController(authService);
    }

    @Test
    void registerJson_success() {
        when(authService.register("alice", "pw", null, null)).thenReturn("u_1");
        AuthDtos.RegisterRequest req = new AuthDtos.RegisterRequest();
        req.setUsername("alice");
        req.setPassword("pw");
        Map<String, Object> resp = controller.registerJson(req);
        assertEquals(true, resp.get("success"));
        assertEquals("u_1", resp.get("userId"));
    }

    @Test
    void registerJson_duplicateUsername() {
        when(authService.register(anyString(), anyString(), isNull(), isNull())).thenReturn(null);
        AuthDtos.RegisterRequest req = new AuthDtos.RegisterRequest();
        req.setUsername("alice");
        req.setPassword("pw");
        Map<String, Object> resp = controller.registerJson(req);
        assertEquals(false, resp.get("success"));
    }

    @Test
    void registerWithAvatar_success() throws Exception {
        when(authService.register(eq("bob"), eq("pw"), any(byte[].class), anyString())).thenReturn("u_2");
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        Map<String, Object> resp = controller.registerWithAvatar("bob", "pw", file);
        assertEquals(true, resp.get("success"));
    }

    @Test
    void login_success() {
        AuthDtos.LoginResponse r = new AuthDtos.LoginResponse();
        r.setToken("t1");
        r.setUserId("u_1");
        when(authService.login("alice", "pw", "desktop")).thenReturn(r);
        AuthDtos.LoginRequest req = new AuthDtos.LoginRequest();
        req.setUsername("alice");
        req.setPassword("pw");
        req.setDeviceType("desktop");
        Map<String, Object> resp = controller.login(req);
        assertEquals(true, resp.get("success"));
        assertEquals("t1", resp.get("token"));
    }

    @Test
    void login_wrongPassword() {
        when(authService.login(anyString(), anyString(), anyString())).thenReturn(null);
        AuthDtos.LoginRequest req = new AuthDtos.LoginRequest();
        req.setUsername("alice");
        req.setPassword("wrong");
        Map<String, Object> resp = controller.login(req);
        assertEquals(false, resp.get("success"));
    }
}
