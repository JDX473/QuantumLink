package com.quantumlink.im.chat.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前用户工具:从 request attribute 取鉴权通过的 userId。
 *
 * <p>所有业务接口必须用这里取 userId,不能信任 URL 参数——
 * 否则"带自己的 token 拉别人的数据"的越权访问无法拦截。
 */
public final class AuthContext {

    private AuthContext() {}

    /** 取当前登录用户 userId(鉴权拦截器已写入) */
    public static String currentUserId(HttpServletRequest request) {
        return (String) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    /** 校验会话归属:单聊会话 ID 是 A#B,当前用户必须是 A 或 B */
    public static boolean isConversationParticipant(String conversationId, String userId) {
        if (conversationId == null || userId == null) return false;
        String[] parts = conversationId.split("#");
        return parts.length == 2 && (parts[0].equals(userId) || parts[1].equals(userId));
    }
}
