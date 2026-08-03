package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.dto.AuthDtos;
import com.quantumlink.im.chat.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody AuthDtos.RegisterRequest request) {
        Map<String, Object> resp = new HashMap<>();
        if (request.getUsername() == null || request.getPassword() == null) {
            resp.put("success", false);
            resp.put("message", "username and password required");
            return resp;
        }
        String userId = authService.register(request.getUsername(), request.getPassword());
        if (userId == null) {
            resp.put("success", false);
            resp.put("message", "username already exists");
            return resp;
        }
        resp.put("success", true);
        resp.put("userId", userId);
        return resp;
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
        return resp;
    }
}
