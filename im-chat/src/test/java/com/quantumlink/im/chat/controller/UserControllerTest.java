package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.config.AuthInterceptor;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.UserMapper;
import com.quantumlink.im.chat.service.AvatarStorageService;
import com.quantumlink.im.chat.service.UserCacheService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserControllerTest {

    private UserMapper userMapper;
    private AvatarStorageService avatarStorage;
    private UserCacheService userCache;
    private HttpServletRequest request;
    private UserController controller;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        avatarStorage = mock(AvatarStorageService.class);
        userCache = mock(UserCacheService.class);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn("u_me");
        controller = new UserController(userMapper, avatarStorage, userCache);
    }

    @Test
    void resolve_found() {
        User u = new User();
        u.setUserId("u_1");
        u.setUsername("alice");
        when(userMapper.selectOne(any())).thenReturn(u);
        Map<String, Object> resp = controller.resolve("alice");
        assertEquals(true, resp.get("success"));
        assertEquals("u_1", resp.get("userId"));
    }

    @Test
    void resolve_notFound() {
        when(userMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> resp = controller.resolve("nobody");
        assertEquals(false, resp.get("success"));
    }

    @Test
    void updateAvatar_otherUser_forbidden() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        Map<String, Object> resp = controller.updateAvatar("u_other", file, request);
        assertEquals(false, resp.get("success"));
        verify(avatarStorage, never()).uploadAvatar(anyString(), any(), anyString());
    }

    @Test
    void updateAvatar_ownUser_success() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});
        when(avatarStorage.uploadAvatar(eq("u_me"), any(), anyString())).thenReturn("http://img/me.png");
        User u = new User();
        u.setUserId("u_me");
        when(userMapper.selectOne(any())).thenReturn(u);
        Map<String, Object> resp = controller.updateAvatar("u_me", file, request);
        assertEquals(true, resp.get("success"));
        assertEquals("http://img/me.png", resp.get("avatarUrl"));
        verify(userCache).invalidate("u_me");
    }

    @Test
    void updateAvatar_emptyFile_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        Map<String, Object> resp = controller.updateAvatar("u_me", file, request);
        assertEquals(false, resp.get("success"));
    }
}
