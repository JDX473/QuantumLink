package com.quantumlink.im.connect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QuantumLink 长连接层启动入口。
 *
 * <p>职责:Netty TCP 长连接服务器(握手鉴权、心跳、EventLoop 异步化、会话注册)。
 * MVP Phase 0 为骨架,Phase 1 实现完整连接层。
 */
public class ConnectServer {
    private static final Logger log = LoggerFactory.getLogger(ConnectServer.class);

    public static void main(String[] args) {
        log.info("QuantumLink im-connect 启动骨架(MVP Phase 0)");
        log.info("Phase 1 将实现:Netty TCP 服务器 / 握手鉴权 / 心跳 / 消息上行");
    }
}
