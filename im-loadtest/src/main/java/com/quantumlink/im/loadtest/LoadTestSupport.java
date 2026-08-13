package com.quantumlink.im.loadtest;

import com.quantumlink.im.common.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 压测公共工具:注册登录 + HTTP 请求 + 分位数。
 *
 * <p>LoadTestClient / GroupLoadTestClient 共用。HttpClient 全局单例复用——
 * 旧实现每次 post new 一个 HttpClient,空连接压测 3 万用户 = 6 万次
 * HttpClient 创建(每个都建连接池/线程),纯浪费。
 */
public class LoadTestSupport {

    /** 共享 HttpClient(复用连接池,默认 5s 建连超时) */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 压测用户(注册登录产物) */
    public static class TestUser {
        public String username, password = "pass123", token, deviceId, userId;
    }

    /** 登录响应解析(与 chat AuthDtos.LoginResponse 对齐) */
    public static class LoginResp {
        public boolean success;
        public String token, deviceId, userId;
    }

    /** 注册 + 登录,返回带 token/deviceId/userId 的用户(用户名 prefix{idx}_{ts},lt 前缀便于 reset-data.sh 清理) */
    public static TestUser registerAndLogin(String prefix, int idx, String apiBase) throws Exception {
        String uname = prefix + idx + "_" + (System.currentTimeMillis() % 1_000_000);
        TestUser u = new TestUser();
        u.username = uname;
        post(apiBase + "/api/auth/register",
                "{\"username\":\"" + uname + "\",\"password\":\"pass123\"}");
        String login = post(apiBase + "/api/auth/login",
                "{\"username\":\"" + uname + "\",\"password\":\"pass123\",\"deviceType\":\"loadtest\"}");
        LoginResp resp = JsonUtil.fromJson(login, LoginResp.class);
        if (resp == null || !resp.success) {
            throw new IllegalStateException("login failed: " + login);
        }
        u.token = resp.token;
        u.deviceId = resp.deviceId;
        u.userId = resp.userId;
        return u;
    }

    /** POST 无鉴权请求(注册/登录) */
    public static String post(String url, String json) throws Exception {
        return post(url, json, null);
    }

    /** POST 带可选 Bearer token(建群等需鉴权接口) */
    public static String post(String url, String json, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(15));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    /** 分位数(sorted 已升序;空数组返回 0) */
    public static double pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
