package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.dto.AuthDtos;
import com.quantumlink.im.chat.entity.Device;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.DeviceMapper;
import com.quantumlink.im.chat.mapper.UserMapper;
import com.quantumlink.im.common.protocol.KickPayload;
import com.quantumlink.im.common.util.JsonUtil;
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
     * 登录:校验密码,签发 token + 绑定/分配 device_id。
     *
     * <p><b>持久 deviceId(多端)</b>:客户端首启生成持久设备 id 存本地,登录时带上
     * ({@code clientDeviceId})——同一个物理设备重装/重登仍被认成同一台设备,设备管理才有意义。
     * 服务端把 clientDeviceId 绑定到账号:该设备已存在则更新 token/活跃时间,否则新建记录。
     * 不传则服务端分配(兼容旧客户端/脚本)。
     *
     * @return 登录成功返回 token/deviceId/userId;失败返回 null
     */
    @Transactional
    public AuthDtos.LoginResponse login(String username, String password, String deviceType, String clientDeviceId) {
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

        // 生成 token;deviceId 优先用客户端持久 id(绑定账号),否则服务端分配
        String token = UUID.randomUUID().toString().replace("-", "");
        String deviceId = resolveDeviceId(user.getUserId(), clientDeviceId);
        long expire = System.currentTimeMillis() + TOKEN_TTL_SECONDS * 1000;

        // 同端类型单设备模式:踢掉同 deviceType 的旧设备(排除正在登录的设备)
        kickSameTypeDevices(user.getUserId(), deviceType, clientDeviceId);

        // 存 Redis:connect 握手时校验 token → userId
        redisTemplate.opsForValue().set("im:token:" + token, user.getUserId(), TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        // 落 device 表:已存在(客户端持久 id 复用)则更新,否则新建
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId)
                        .eq(Device::getUserId, user.getUserId()));
        boolean isNew = device == null;
        if (isNew) {
            device = new Device();
            device.setDeviceId(deviceId);
            device.setUserId(user.getUserId());
            device.setDeviceType(deviceType == null || deviceType.isEmpty() ? "desktop" : deviceType);
            device.setCreatedAt(LocalDateTime.now());
        } else if (deviceType != null && !deviceType.isEmpty()) {
            device.setDeviceType(deviceType);
        }
        device.setToken(token);
        device.setTokenExpire(expire);
        device.setLastActiveAt(LocalDateTime.now());
        if (isNew) {
            deviceMapper.insert(device);
        } else {
            deviceMapper.updateById(device);
        }

        AuthDtos.LoginResponse resp = new AuthDtos.LoginResponse();
        resp.setToken(token);
        resp.setDeviceId(deviceId);
        resp.setUserId(user.getUserId());
        resp.setUsername(user.getUsername());
        resp.setAvatarUrl(user.getAvatarUrl());
        log.info("login ok: userId={} deviceId={} {}", user.getUserId(), deviceId, isNew ? "(new device)" : "(existing device)");
        return resp;
    }

    /**
     * 设备 id:客户端持久 id 合法则用它(绑定账号——已存在同账号设备则复用,保证
     * 同一物理设备重装/重登被认成同一台);否则服务端分配。
     */
    private String resolveDeviceId(String userId, String clientDeviceId) {
        if (clientDeviceId != null && !clientDeviceId.isEmpty()) {
            return clientDeviceId;
        }
        return "d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 我的设备列表:含在线状态(Redis 会话表:设备在 im:devices 且会话 key 存活 = 在线) */
    public java.util.List<java.util.Map<String, Object>> listDevices(String userId) {
        java.util.List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getUserId, userId)
                        .orderByDesc(Device::getLastActiveAt));
        java.util.Set<String> online = redisTemplate.opsForSet().members("im:devices:" + userId);
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (Device d : devices) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("deviceId", d.getDeviceId());
            m.put("deviceType", d.getDeviceType());
            m.put("lastActiveAt", d.getLastActiveAt());
            boolean isOnline = online != null && online.contains(d.getDeviceId())
                    && redisTemplate.opsForValue().get("im:session:" + userId + ":" + d.getDeviceId()) != null;
            m.put("online", isOnline);
            result.add(m);
        }
        return result;
    }

    // ==================== 多端踢人(同端类型单设备模式 / 手动踢设备) ====================

    /**
     * 发布踢人指令到 Redis Pub/Sub {@code im:kick} 频道。
     * 所有 connect 节点订阅并收到,各节点本地判目标——只有持有该连接的节点才关。
     * 即发即弃:踢不到也靠"删 token"兜底(重连握手被拒,最终出局)。
     */
    public void publishKick(String userId, String deviceId) {
        redisTemplate.convertAndSend("im:kick", JsonUtil.toJson(new KickPayload(userId, deviceId)));
        log.info("kick published: user={} device={}", userId, deviceId);
    }

    /**
     * 踢同账号同 deviceType 的旧设备(单设备模式:同端类型只能一台在线)。
     * 删旧 token(让重连被拒)+ publish KICK(断当前连接)。排除正在登录/复用的设备。
     */
    public void kickSameTypeDevices(String userId, String deviceType, String excludeDeviceId) {
        if (deviceType == null || deviceType.isEmpty()) {
            return;
        }
        java.util.List<Device> sameType = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getUserId, userId)
                        .eq(Device::getDeviceType, deviceType));
        for (Device d : sameType) {
            if (excludeDeviceId != null && excludeDeviceId.equals(d.getDeviceId())) {
                continue; // 正在登录/复用的设备不踢自己
            }
            if (d.getToken() != null) {
                redisTemplate.delete("im:token:" + d.getToken());
            }
            publishKick(userId, d.getDeviceId());
        }
        if (!sameType.isEmpty()) {
            log.info("kicked same-type devices: user={} type={} count={}", userId, deviceType, sameType.size());
        }
    }

    /** 手动踢某台设备:删其 token + publish KICK。只能踢自己的设备(调用方已校验) */
    public boolean kickDevice(String userId, String deviceId) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId)
                        .eq(Device::getUserId, userId));
        if (device == null) {
            return false;
        }
        if (device.getToken() != null) {
            redisTemplate.delete("im:token:" + device.getToken());
        }
        publishKick(userId, deviceId);
        return true;
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
