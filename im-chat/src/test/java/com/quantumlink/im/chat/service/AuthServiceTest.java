package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.dto.AuthDtos;
import com.quantumlink.im.chat.entity.Device;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.DeviceMapper;
import com.quantumlink.im.chat.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserMapper userMapper;
    private DeviceMapper deviceMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private AvatarStorageService avatarStorageService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        avatarStorageService = mock(AvatarStorageService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        authService = new AuthService(userMapper, deviceMapper, redisTemplate, avatarStorageService);
    }

    @Test
    void register_success() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        String userId = authService.register("alice", "pass123", null, null);
        assertNotNull(userId);
        assertTrue(userId.startsWith("u_"));
        verify(userMapper).insert(any(User.class));
        verify(avatarStorageService, never()).uploadAvatar(anyString(), any(), anyString());
    }

    @Test
    void register_duplicateUsername_returnsNull() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        assertNull(authService.register("alice", "pass123", null, null));
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_withAvatar() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(avatarStorageService.uploadAvatar(anyString(), any(), anyString())).thenReturn("http://img/a.png");
        String userId = authService.register("bob", "pw", new byte[]{1, 2, 3}, "image/png");
        assertNotNull(userId);
        verify(avatarStorageService).uploadAvatar(eq(userId), any(), eq("image/png"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("http://img/a.png", captor.getValue().getAvatarUrl());
    }

    @Test
    void login_success() {
        User user = new User();
        user.setUserId("u_1");
        user.setUsername("alice");
        user.setPasswordHash("salt123:" + realHash("salt123", "pw")); // 真实哈希:格式 salt:hash
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(user);

        AuthDtos.LoginResponse resp = authService.login("alice", "pw", "desktop");
        assertNotNull(resp);
        assertEquals("u_1", resp.getUserId());
        assertEquals("alice", resp.getUsername());
        assertNotNull(resp.getToken());
        assertTrue(resp.getDeviceId().startsWith("d_"));
        verify(deviceMapper).insert(any(Device.class));
        verify(valueOps).set(startsWith("im:token:"), eq("u_1"), anyLong(), any(TimeUnit.class));
    }

    /** 与 AuthService.hash 相同的 SHA-256 计算(测试用真实哈希,避免 mock 密码不匹配) */
    private static String realHash(String salt, String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((salt + input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void login_userNotFound_returnsNull() {
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(null);
        assertNull(authService.login("nobody", "pw", "desktop"));
    }

    @Test
    void login_wrongPassword_returnsNull() {
        User user = new User();
        user.setPasswordHash("salt123:differenthash");
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(user);
        assertNull(authService.login("alice", "wrong", "desktop"));
        verify(deviceMapper, never()).insert(any(Device.class));
    }

    @Test
    void login_badStoredHashFormat_returnsNull() {
        User user = new User();
        user.setPasswordHash("no-colon-here");
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(user);
        assertNull(authService.login("alice", "pw", "desktop"));
    }

    @Test
    void login_passwordHash_matchesStored() {
        // 真实哈希校验:注册的密码能登录
        when(userMapper.selectCount(any())).thenReturn(0L);
        String userId = authService.register("alice", "pass123", null, null);
        assertNotNull(userId);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User stored = captor.getValue();

        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(stored);
        AuthDtos.LoginResponse resp = authService.login("alice", "pass123", null);
        assertNotNull(resp);
        assertEquals(userId, resp.getUserId());
        assertEquals("desktop", resp.getDeviceId().startsWith("d_") ? "desktop" : resp.getDeviceId());
    }

    @Test
    void login_deviceTypeDefault_desktop() {
        User user = new User();
        user.setUserId("u_1");
        user.setPasswordHash("s:" + realHash("s", "pw")); // 真实哈希,密码 "pw" 能通过
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(user);
        AuthDtos.LoginResponse resp = authService.login("alice", "pw", null);
        assertNotNull(resp);
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceMapper).insert(captor.capture());
        assertEquals("desktop", captor.getValue().getDeviceType());
    }
}
