package com.quantumlink.im.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户查询接口。
 *
 * <p>提供"用户名 → userId"解析:聊天时用户填的是用户名(可变、好记),
 * 前端调此接口解析成 userId(不变、服务端分配的稳定身份)再发送。
 * 整个消息链路仍以 userId 为身份锚点,用户改名不影响历史消息/会话/设备。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;

    /**
     * 按用户名解析用户。
     *
     * @param username 用户名(可变,对外可见)
     * @return { success, userId, username };不存在时 success=false
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
        return resp;
    }
}
