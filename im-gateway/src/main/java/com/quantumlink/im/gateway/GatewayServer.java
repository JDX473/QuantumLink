package com.quantumlink.im.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QuantumLink 入口代理(骨架,MVP 后置)。
 * Phase 4 实现:Netty TCP 转发 + 负载均衡 + Nacos 动态路由。
 */
public class GatewayServer {
    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    public static void main(String[] args) {
        log.info("QuantumLink im-gateway 骨架(Phase 0),MVP 后置实现");
    }
}
