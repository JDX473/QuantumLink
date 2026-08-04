package com.quantumlink.im.chat.controller;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.quantumlink.im.chat.service.NacosDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长连接调度接口(最少连接)。
 *
 * <p>客户端连接前调用本接口,拿到"该连哪个 connect 节点"。这是
 * "无 gateway 的客户端直连"模式,决策完全在服务端:
 * <ul>
 *   <li><b>节点清单</b>:来自 Nacos(connect 启动注册 {@code im-connect},动态发现 + 健康检查);</li>
 *   <li><b>负载指标</b>:来自 Redis(connect 每 1s 上报连接数 {@code im:node:conns:{nodeId}});</li>
 *   <li><b>决策</b>:取连接数最少的节点,客户端照单直连 —— 节点列表对客户端透明。</li>
 * </ul>
 *
 * <p>为什么最少连接:接入层长连接无状态(消息路由靠 Redis 会话表,不粘节点),
 * 随机分配会造成热点(新节点分不到流量/旧节点过载)。最少连接感知每个节点
 * 当前的并发连接数,让新连接落在最空的节点。连接数是瞬时指标,1s 弱一致上报
 * 足够(不需要精确到条)。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConnectController {

    /** Redis 节点连接数 key 前缀(与 connect NodeReporter 共用) */
    private static final String NODE_CONNS_PREFIX = "im:node:conns:";

    private final NacosDiscoveryService nacosDiscoveryService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 最少连接决策:从 Nacos 拿在线实例,查 Redis 连接数,返回连接最少的节点。
     *
     * @return 成功:{ success:true, address, nodeId, connections };无节点:{ success:false }
     */
    @GetMapping("/connects")
    public Map<String, Object> connects() {
        Map<String, Object> resp = new HashMap<>();
        List<Instance> instances = nacosDiscoveryService.listHealthyInstances();
        if (instances.isEmpty()) {
            log.warn("no healthy connect node found in nacos, reject dispatch");
            resp.put("success", false);
            resp.put("reason", "no healthy connect node");
            return resp;
        }

        // 每个实例查实时连接数,取最少(同连接数时保留先遍历到的)
        Instance best = null;
        String bestNodeId = null;
        int bestConns = Integer.MAX_VALUE;
        for (Instance instance : instances) {
            String nodeId = instance.getIp() + ":" + instance.getPort();
            String connsStr = redisTemplate.opsForValue().get(NODE_CONNS_PREFIX + nodeId);
            int conns = connsStr == null ? 0 : Integer.parseInt(connsStr);
            log.debug("node candidate: nodeId={} connections={}", nodeId, conns);
            if (conns < bestConns) {
                best = instance;
                bestNodeId = nodeId;
                bestConns = conns;
            }
        }

        resp.put("success", true);
        resp.put("address", best.getIp() + ":" + best.getPort());
        resp.put("nodeId", bestNodeId);
        resp.put("connections", bestConns);
        log.info("dispatch least-connections: address={} connections={}", resp.get("address"), bestConns);
        return resp;
    }
}
