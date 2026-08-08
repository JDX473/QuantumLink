package com.quantumlink.im.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置:注册鉴权拦截器。
 *
 * <p>放行规则:
 * <ul>
 *   <li>{@code /api/auth/**}:注册/登录(登录前没有 token);</li>
 *   <li>其余 {@code /api/**}:必须带合法 token,否则 401。</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 只放行无需登录的鉴权端点(注册/登录/带头像注册);其余 /api/auth/devices 等仍需 token
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/register/avatar",
                        "/api/auth/login");
    }
}
