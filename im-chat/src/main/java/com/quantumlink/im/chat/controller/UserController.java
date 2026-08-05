package com.quantumlink.im.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.config.AuthContext;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.UserMapper;
import com.quantumlink.im.chat.service.AvatarStorageService;
import com.quantumlink.im.chat.service.UserCacheService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户接口:查询、改头像。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final AvatarStorageService avatarStorageService;
    private final UserCacheService userCacheService;

    /**
     * 按用户名解析用户。
     *
     * @param username 用户名(可变,对外可见)
     * @return { success, userId, username, avatarUrl };不存在时 success=false
     */
    @GetMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam("username") String username) {
        Map<String, Object> resp = new HashMap<>();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            resp.put("success", false);
            resp.put("message", "user not found: " + username);
            return resp;
        }
        resp.put("success", true);
        resp.put("userId", user.getUserId());
        resp.put("username", user.getUsername());
        resp.put("avatarUrl", user.getAvatarUrl());
        return resp;
    }

    /**
     * 修改头像:上传文件 → 存 MinIO → 更新用户 avatar_url。
     * 越权防护:只能改自己的头像(userId 从鉴权上下文取,不信任 URL 参数)。
     */
    @PostMapping("/{userId}/avatar")
    public Map<String, Object> updateAvatar(
            @PathVariable("userId") String userId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        String currentUserId = AuthContext.currentUserId(request);
        if (!userId.equals(currentUserId)) {
            resp.put("success", false);
            resp.put("message", "forbidden: can only update own avatar");
            return resp;
        }
        try {
            if (file == null || file.isEmpty()) {
                resp.put("success", false);
                resp.put("message", "file required");
                return resp;
            }
            String avatarUrl = avatarStorageService.uploadAvatar(userId, file.getBytes(), file.getContentType());
            // 更新用户表 + 删用户资料缓存(头像变了,缓存要失效)
            User user = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getUserId, userId));
            if (user != null) {
                user.setAvatarUrl(avatarUrl);
                userMapper.updateById(user);
                userCacheService.invalidate(userId);
            }
            resp.put("success", true);
            resp.put("avatarUrl", avatarUrl);
            return resp;
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "upload failed: " + e.getMessage());
            return resp;
        }
    }
}
