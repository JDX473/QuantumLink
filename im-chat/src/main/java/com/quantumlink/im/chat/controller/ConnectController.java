package com.quantumlink.im.chat.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 长连接调度接口。
 *
 * <p>客户端连接前调用本接口获取可连的 connect 节点列表,挑一个直连。
 * 这是"无 gateway 的客户端直连"模式:
 * <ul>
 *   <li>connect 是无状态接入层,客户端连哪个节点都行(消息路由靠 Redis 会话表);</li>
 *   <li>调度接口做负载均衡(返回节点列表,客户端随机/轮询选择);</li>
 *   <li>MVP 用配置驱动(静态节点列表),后续可接 Nacos 动态发现。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ConnectController {

    private final List<String> connectNodes;

    public ConnectController(@Value("${connect.nodes:}") List<String> connectNodes) {
        this.connectNodes = connectNodes;
    }

    /**
     * 获取可连节点列表。
     *
     * @return { success, nodes: [{ address, nodeId }] }
     */
    @GetMapping("/connects")
    public Map<String, Object> connects() {
        Map<String, Object> resp = new HashMap<>();
        List<Map<String, String>> nodes = new ArrayList<>();
        for (String addr : connectNodes) {
            Map<String, String> node = new HashMap<>();
            node.put("address", addr);
            node.put("nodeId", addr); // nodeId = host:port(与 connect 会话表/MQ tag 一致)
            nodes.add(node);
        }
        resp.put("success", true);
        resp.put("nodes", nodes);
        return resp;
    }
}
