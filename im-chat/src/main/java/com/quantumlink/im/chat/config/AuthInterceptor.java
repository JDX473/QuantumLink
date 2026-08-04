package com.quantumlink.im.chat.config;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP 鉴权拦截器:校验 {@code Authorization: Bearer {token}}。
 *
 * <p>与 TCP 握手鉴权共用同一套 token:登录时存 Redis {@code im:token:{token}=userId}。
 * <ul>
 *   <li>放行:{@code /api/auth/**}(注册/登录不需要 token)、静态资源;</li>
 *   <li>其余接口:无 token / token 无效 → 401 JSON;有效 → 把 userId 放进
 *       request attribute({@link #ATTR_USER_ID}),Controller 可直接取。</li>
 * </ul>
 *
 * <p>为什么要 HTTP 鉴权:业务接口(拉消息/拉会话/建群)直接暴露 userId 维度数据,
 * 没有鉴权 = 任何人能拉任意用户的聊天记录。这是 IM 的安全底线。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 校验通过后,userId 存 request attribute 的 key */
    public static final String ATTR_USER_ID = "auth.userId";

    private static final String TOKEN_PREFIX = "im:token:";
    private static final String BEARER = "Bearer ";

    private final StringRedisTemplate redisTemplate;

    public AuthInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS(CORS 预检,无 token)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith(BEARER)) {
            token = header.substring(BEARER.length()).trim();
        }
        if (token == null || token.isEmpty()) {
            writeUnauthorized(response, "missing token");
            return false;
        }

        // 校验:Redis im:token:{token} → userId
        String userId = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);
        if (userId == null) {
            writeUnauthorized(response, "invalid or expired token");
            return false;
        }

        request.setAttribute(ATTR_USER_ID, userId);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
