package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.dto.AuthDtos;
import com.quantumlink.im.chat.entity.Device;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.DeviceMapper;
import com.quantumlink.im.chat.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务:注册、登录、token 签发。
 *
 * <p>流程:
 * <ul>
 *   <li>注册:校验用户名唯一 → 创建用户(服务端分配 user_id);</li>
 *   <li>登录:校验密码 → 生成 token(UUID)→ 分配 device_id → 存 Redis
 *       {@code im:token:{token}=userId}(connect 握手时校验)→ 落 device 表;</li>
 *   <li>返回 token + deviceId + userId,客户端握手时携带。</li>
 * </ul>
 *
 * <p>密码用 SHA-256 + 随机 salt 哈希存储,不存明文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final StringRedisTemplate redisTemplate;
    private final AvatarStorageService avatarStorageService;

    /** token 有效期:30 天 */
    private static final long TOKEN_TTL_SECONDS = 30L * 24 * 3600;

    /**
     * 注册新用户。
     *
     * @param username    用户名
     * @param password    密码
     * @param avatarData  头像字节(可为 null)
     * @param avatarType 头像 MIME 类型(可为 null)
     * @return 分配的 user_id;用户名已存在返回 null
     */
    public String register(String username, String password, byte[] avatarData, String avatarType) {
        // 校验用户名唯一
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (count != null && count > 0) {
            return null;
        }

        // 生成 salt + 密码哈希
        String salt = UUID.randomUUID().toString().substring(0, 8);
        String passwordHash = hash(password, salt);

        User user = new User();
        user.setUserId("u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        user.setUsername(username);
        user.setPasswordHash(salt + ":" + passwordHash);
        user.setCreatedAt(LocalDateTime.now());

        // 注册时可选上传头像
        if (avatarData != null && avatarData.length > 0) {
            String avatarUrl = avatarStorageService.uploadAvatar(user.getUserId(), avatarData, avatarType);
            user.setAvatarUrl(avatarUrl);
        }

        userMapper.insert(user);
        log.info("user registered: userId={} username={}", user.getUserId(), username);
        return user.getUserId();
    }

    /**
     * 登录:校验密码,签发 token + 分配 device_id。
     *
     * @return 登录成功返回 token/deviceId/userId;失败返回 null
     */
    @Transactional
    public AuthDtos.LoginResponse login(String username, String password, String deviceType) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            log.warn("login failed: user not found: {}", username);
            return null;
        }

        // 校验密码
        String[] parts = user.getPasswordHash().split(":", 2);
        if (parts.length != 2) {
            log.warn("login failed: bad stored hash for {}", username);
            return null;
        }
        if (!hash(password, parts[0]).equals(parts[1])) {
            log.warn("login failed: wrong password: {}", username);
            return null;
        }

        // 生成 token + device_id
        String token = UUID.randomUUID().toString().replace("-", "");
        String deviceId = "d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long expire = System.currentTimeMillis() + TOKEN_TTL_SECONDS * 1000;

        // 存 Redis:connect 握手时校验 token → userId
        redisTemplate.opsForValue().set("im:token:" + token, user.getUserId(), TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        // 落 device 表
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setUserId(user.getUserId());
        device.setDeviceType(deviceType == null || deviceType.isEmpty() ? "desktop" : deviceType);
        device.setToken(token);
        device.setTokenExpire(expire);
        device.setLastActiveAt(LocalDateTime.now());
        device.setCreatedAt(LocalDateTime.now());
        deviceMapper.insert(device);

        AuthDtos.LoginResponse resp = new AuthDtos.LoginResponse();
        resp.setToken(token);
        resp.setDeviceId(deviceId);
        resp.setUserId(user.getUserId());
        resp.setUsername(user.getUsername());
        resp.setAvatarUrl(user.getAvatarUrl());
        log.info("login ok: userId={} deviceId={}", user.getUserId(), deviceId);
        return resp;
    }

    /** SHA-256 哈希:input + salt */
    private String hash(String input, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((salt + input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hash failed", e);
        }
    }
}
