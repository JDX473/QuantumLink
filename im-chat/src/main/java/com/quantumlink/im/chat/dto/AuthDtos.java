package com.quantumlink.im.chat.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 认证 DTO:注册/登录请求与响应。
 */
public class AuthDtos {

    /** 注册请求 */
    @Getter
    @Setter
    public static class RegisterRequest {
        private String username;
        private String password;
    }

    /** 登录请求 */
    @Getter
    @Setter
    public static class LoginRequest {
        private String username;
        private String password;
        private String deviceType; // web / desktop / mobile
        /** 客户端生成的持久设备 id(存本地,重装/重登不变);不传则服务端分配 */
        private String deviceId;
    }

    /** 登录响应:客户端握手时携带 token + deviceId */
    @Getter
    @Setter
    public static class LoginResponse {
        private String token;
        private String deviceId;
        private String userId;
        private String username;
        private String avatarUrl;
    }
}
