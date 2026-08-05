package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserCacheServiceTest {

    private UserMapper userMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private UserCacheService cache;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache = new UserCacheService(userMapper, redisTemplate);
    }

    @Test
    void getUser_cacheHit() {
        when(valueOps.get("im:user:u1")).thenReturn("{\"username\":\"alice\",\"avatarUrl\":\"http://a.png\"}");
        UserCacheService.UserView view = cache.getUser("u1");
        assertNotNull(view);
        assertEquals("alice", view.getUsername());
        assertEquals("http://a.png", view.getAvatarUrl());
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void getUser_cacheMiss_queriesDbAndBackfills() {
        when(valueOps.get("im:user:u1")).thenReturn(null);
        User user = new User();
        user.setUserId("u1");
        user.setUsername("bob");
        user.setAvatarUrl("http://b.png");
        when(userMapper.selectOne(any())).thenReturn(user);

        UserCacheService.UserView view = cache.getUser("u1");
        assertNotNull(view);
        assertEquals("bob", view.getUsername());
        assertEquals("http://b.png", view.getAvatarUrl());
        // 回填缓存
        verify(valueOps).set(eq("im:user:u1"), contains("bob"), eq(600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void getUser_userNotFound_returnsNull() {
        when(valueOps.get("im:user:u1")).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(null);
        assertNull(cache.getUser("u1"));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void getUser_cacheHit_corruptJson_fallsBackToDb() {
        when(valueOps.get("im:user:u1")).thenReturn("{corrupt");
        User user = new User();
        user.setUsername("bob");
        when(userMapper.selectOne(any())).thenReturn(user);
        UserCacheService.UserView view = cache.getUser("u1");
        assertNotNull(view);
        assertEquals("bob", view.getUsername());
    }

    @Test
    void invalidate_deletesKey() {
        cache.invalidate("u1");
        verify(redisTemplate).delete("im:user:u1");
    }
}
