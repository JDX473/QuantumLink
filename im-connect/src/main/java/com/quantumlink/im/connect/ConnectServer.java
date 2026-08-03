package com.quantumlink.im.connect;

import com.quantumlink.im.connect.config.ConnectConfig;
import com.quantumlink.im.connect.server.NettyConnectServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QuantumLink 长连接层启动入口。
 *
 * <p>职责:Netty TCP 长连接服务器(握手鉴权、心跳、EventLoop 异步化、会话注册、消息上行)。
 * 用法:{@code java -Dim.connect.port=9999 -jar im-connect.jar}
 */
public class ConnectServer {
    private static final Logger log = LoggerFactory.getLogger(ConnectServer.class);

    public static void main(String[] args) {
        ConnectConfig config = ConnectConfig.fromEnv();
        NettyConnectServer server = new NettyConnectServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "connect-shutdown"));

        try {
            server.start();
        } catch (Exception e) {
            log.error("im-connect failed to start", e);
            server.shutdown();
            System.exit(1);
        }
    }
}
