package com.quantumlink.im.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.UserMapper;
import com.quantumlink.im.chat.service.AvatarStorageService;
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
     *
     * @param userId 用户 ID
     * @param file   头像文件
     * @return { success, avatarUrl }
     */
    @PostMapping("/{userId}/avatar")
    public Map<String, Object> updateAvatar(
            @PathVariable("userId") String userId,
            @RequestParam("file") MultipartFile file) {
        Map<String, Object> resp = new HashMap<>();
        try {
            if (file == null || file.isEmpty()) {
                resp.put("success", false);
                resp.put("message", "file required");
                return resp;
            }
            String avatarUrl = avatarStorageService.uploadAvatar(userId, file.getBytes(), file.getContentType());
            // 更新用户表
            User user = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getUserId, userId));
            if (user != null) {
                user.setAvatarUrl(avatarUrl);
                userMapper.updateById(user);
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
