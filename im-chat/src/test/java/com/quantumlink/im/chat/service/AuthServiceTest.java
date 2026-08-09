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

        AuthDtos.LoginResponse resp = authService.login("alice", "pw", "desktop", null);
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
        assertNull(authService.login("nobody", "pw", "desktop", null));
    }

    @Test
    void login_wrongPassword_returnsNull() {
        User user = new User();
        user.setPasswordHash("salt123:differenthash");
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(user);
        assertNull(authService.login("alice", "wrong", "desktop", null));
        verify(deviceMapper, never()).insert(any(Device.class));
    }

    @Test
    void login_badStoredHashFormat_returnsNull() {
        User user = new User();
        user.setPasswordHash("no-colon-here");
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(user);
        assertNull(authService.login("alice", "pw", "desktop", null));
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
        AuthDtos.LoginResponse resp = authService.login("alice", "pass123", null, null);
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
        AuthDtos.LoginResponse resp = authService.login("alice", "pw", null, null);
        assertNotNull(resp);
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceMapper).insert(captor.capture());
        assertEquals("desktop", captor.getValue().getDeviceType());
    }

    // ==================== 多端:持久 deviceId + 设备列表 ====================

    @Test
    void login_withClientDeviceId_reusesSameDevice() {
        User user = new User();
        user.setUserId("u_1");
        user.setPasswordHash("s:" + realHash("s", "pw"));
        when(userMapper.selectOne(any())).thenReturn(user);
        // 该客户端持久 deviceId 已绑定过本账号 → 复用,不新建
        Device exist = new Device();
        exist.setId(1L);
        exist.setDeviceId("d_persist123");
        exist.setUserId("u_1");
        when(deviceMapper.selectOne(any())).thenReturn(exist);

        AuthDtos.LoginResponse resp = authService.login("alice", "pw", "desktop", "d_persist123");

        assertEquals("d_persist123", resp.getDeviceId());
        verify(deviceMapper, never()).insert(any(Device.class));
        verify(deviceMapper).updateById(any(Device.class)); // 复用:更新 token/活跃时间
    }

    @Test
    void login_withClientDeviceId_newDevice_binds() {
        User user = new User();
        user.setUserId("u_1");
        user.setPasswordHash("s:" + realHash("s", "pw"));
        when(userMapper.selectOne(any())).thenReturn(user);
        when(deviceMapper.selectOne(any())).thenReturn(null); // 该设备第一次登录 → 新建绑定

        AuthDtos.LoginResponse resp = authService.login("alice", "pw", "desktop", "d_client123");

        assertEquals("d_client123", resp.getDeviceId());
        verify(deviceMapper).insert(any(Device.class));
    }

    @Test
    void listDevices_withOnlineStatus() {
        Device d1 = new Device();
        d1.setDeviceId("d_1");
        d1.setDeviceType("desktop");
        Device d2 = new Device();
        d2.setDeviceId("d_2");
        d2.setDeviceType("mobile");
        when(deviceMapper.selectList(any())).thenReturn(java.util.List.of(d1, d2));
        org.springframework.data.redis.core.SetOperations<String, String> setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("im:devices:u_1")).thenReturn(new java.util.HashSet<>(java.util.List.of("d_1")));
        when(valueOps.get("im:session:u_1:d_1")).thenReturn("127.0.0.1:19001"); // d_1 在线
        when(valueOps.get("im:session:u_1:d_2")).thenReturn(null); // d_2 离线

        var devices = authService.listDevices("u_1");

        assertEquals(2, devices.size());
        java.util.Map<String, Object> m1 = devices.get(0);
        java.util.Map<String, Object> m2 = devices.get(1);
        assertEquals(true, m1.get("online"));
        assertEquals(false, m2.get("online"));
        assertTrue(m1.containsKey("deviceId") && m1.containsKey("deviceType") && m1.containsKey("lastActiveAt"));
    }

    // ==================== 多端踢人 ====================

    @Test
    void login_kicksSameTypeDevices() {
        User user = new User();
        user.setUserId("u_1");
        user.setPasswordHash("s:" + realHash("s", "pw"));
        when(userMapper.selectOne(any())).thenReturn(user);
        // 真实查询按 device_type=mobile 过滤 → 只有同类型旧设备被返回
        Device oldMobile = new Device();
        oldMobile.setDeviceId("d_old1"); oldMobile.setDeviceType("mobile"); oldMobile.setToken("tok_old1"); oldMobile.setUserId("u_1");
        when(deviceMapper.selectList(any())).thenReturn(java.util.List.of(oldMobile));
        when(deviceMapper.selectOne(any())).thenReturn(null); // 新设备无旧行

        authService.login("alice", "pw", "mobile", "d_new");

        // 踢同类型 mobile 旧设备:删 token + publish KICK
        verify(redisTemplate).delete("im:token:tok_old1");
        verify(redisTemplate).convertAndSend(eq("im:kick"), contains("d_old1"));
    }

    @Test
    void login_doesNotKickSelf() {
        User user = new User();
        user.setUserId("u_1");
        user.setPasswordHash("s:" + realHash("s", "pw"));
        when(userMapper.selectOne(any())).thenReturn(user);
        // 同类型旧设备就是正在登录/复用的 d_self
        Device self = new Device();
        self.setDeviceId("d_self"); self.setDeviceType("desktop"); self.setToken("tok_self"); self.setUserId("u_1");
        when(deviceMapper.selectList(any())).thenReturn(java.util.List.of(self));
        when(deviceMapper.selectOne(any())).thenReturn(self); // 复用 d_self

        authService.login("alice", "pw", "desktop", "d_self");

        verify(redisTemplate, never()).delete("im:token:tok_self");
        verify(redisTemplate, never()).convertAndSend(eq("im:kick"), anyString());
    }

    @Test
    void kickDevice_removesTokenAndPublishes() {
        Device device = new Device();
        device.setDeviceId("d_1"); device.setToken("tok_1"); device.setUserId("u_1");
        when(deviceMapper.selectOne(any())).thenReturn(device);

        boolean ok = authService.kickDevice("u_1", "d_1");

        assertTrue(ok);
        verify(redisTemplate).delete("im:token:tok_1");
        verify(redisTemplate).convertAndSend(eq("im:kick"), contains("d_1"));
    }

    @Test
    void kickDevice_notFound_returnsFalse() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        assertFalse(authService.kickDevice("u_1", "d_x"));
        verify(redisTemplate, never()).delete(anyString());
    }
}
