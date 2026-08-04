package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.dto.AuthDtos;
import com.quantumlink.im.chat.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口:注册、登录。
 *
 * <p>登录返回 token + deviceId + userId,客户端建立长连接握手时携带。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册(JSON 方式,无头像):兼容脚本 */
    @PostMapping("/register")
    public Map<String, Object> registerJson(@RequestBody AuthDtos.RegisterRequest request) {
        Map<String, Object> resp = new HashMap<>();
        if (request.getUsername() == null || request.getPassword() == null) {
            resp.put("success", false);
            resp.put("message", "username and password required");
            return resp;
        }
        String userId = authService.register(request.getUsername(), request.getPassword(), null, null);
        if (userId == null) {
            resp.put("success", false);
            resp.put("message", "username already exists");
            return resp;
        }
        resp.put("success", true);
        resp.put("userId", userId);
        return resp;
    }

    /** 注册(multipart 方式,头像可选)。表单字段:username, password, 可选 file */
    @PostMapping(value = "/register/avatar", consumes = {"multipart/form-data"})
    public Map<String, Object> registerWithAvatar(@RequestParam("username") String username,
                                                  @RequestParam("password") String password,
                                                  @RequestParam(value = "file", required = false) MultipartFile file) {
        Map<String, Object> resp = new HashMap<>();
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "username and password required");
            return resp;
        }
        try {
            byte[] avatarData = (file != null && !file.isEmpty()) ? file.getBytes() : null;
            String avatarType = (file != null) ? file.getContentType() : null;
            String userId = authService.register(username, password, avatarData, avatarType);
            if (userId == null) {
                resp.put("success", false);
                resp.put("message", "username already exists");
                return resp;
            }
            resp.put("success", true);
            resp.put("userId", userId);
            return resp;
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "register failed: " + e.getMessage());
            return resp;
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody AuthDtos.LoginRequest request) {
        Map<String, Object> resp = new HashMap<>();
        AuthDtos.LoginResponse result = authService.login(
                request.getUsername(), request.getPassword(), request.getDeviceType());
        if (result == null) {
            resp.put("success", false);
            resp.put("message", "invalid username or password");
            return resp;
        }
        resp.put("success", true);
        resp.put("token", result.getToken());
        resp.put("deviceId", result.getDeviceId());
        resp.put("userId", result.getUserId());
        resp.put("username", result.getUsername());
        resp.put("avatarUrl", result.getAvatarUrl());
        return resp;
    }
}
