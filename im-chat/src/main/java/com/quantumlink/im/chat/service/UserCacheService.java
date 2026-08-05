package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.UserMapper;
import com.quantumlink.im.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户资料缓存(Redis):避免每条消息查 DB 填充发送者名字/头像。
 *
 * <p>为什么需要:消息链路每条消息 fillSenderProfile 查一次 User 表,
 * 压测显示 chat CPU 57% 含大量 DB 查询。用户资料基本不变,Redis 缓存命中
 * 后不再查 DB。头像/昵称变更时删 key 失效。
 *
 * <p>key:{@code im:user:{userId}} → JSON{username, avatarUrl},TTL 10 分钟。
 * 只缓存 UI 需要的字段(不序列化整个 User——LocalDateTime 字段 Jackson 序列化失败)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCacheService {

    private static final String USER_CACHE_PREFIX = "im:user:";
    private static final long TTL_SECONDS = 600;

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    /** 查用户资料(先缓存,miss 查 DB 回填);返回轻量视图(username/avatarUrl) */
    public UserView getUser(String userId) {
        // 1. 缓存命中
        String cached = redisTemplate.opsForValue().get(USER_CACHE_PREFIX + userId);
        if (cached != null) {
            try {
                Map<?, ?> map = JsonUtil.fromJson(cached, Map.class);
                UserView view = new UserView();
                view.setUserId(userId);
                view.setUsername((String) map.get("username"));
                view.setAvatarUrl((String) map.get("avatarUrl"));
                return view;
            } catch (Exception e) {
                log.warn("parse user cache failed: user={}", userId, e);
            }
        }

        // 2. miss:查 DB + 回填缓存(只存 UI 字段,不序列化整个 User)
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUserId, userId));
        if (user != null) {
            UserView view = new UserView();
            view.setUserId(userId);
            view.setUsername(user.getUsername());
            view.setAvatarUrl(user.getAvatarUrl());
            try {
                Map<String, String> cacheValue = new HashMap<>();
                cacheValue.put("username", user.getUsername());
                cacheValue.put("avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl());
                redisTemplate.opsForValue().set(USER_CACHE_PREFIX + userId,
                        JsonUtil.toJson(cacheValue), TTL_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("write user cache failed: user={}", userId, e);
            }
            return view;
        }
        return null;
    }

    /** 用户资料变更(改头像等)时删缓存 */
    public void invalidate(String userId) {
        try {
            redisTemplate.delete(USER_CACHE_PREFIX + userId);
        } catch (Exception e) {
            log.warn("invalidate user cache failed: user={}", userId, e);
        }
    }

    /** 用户资料轻量视图(缓存/下行填充用,避免序列化 User 的 LocalDateTime) */
    @lombok.Getter
    @lombok.Setter
    public static class UserView {
        private String userId;
        private String username;
        private String avatarUrl;
    }
}
