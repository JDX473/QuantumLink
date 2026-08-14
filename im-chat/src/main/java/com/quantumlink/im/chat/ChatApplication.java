package com.quantumlink.im.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * QuantumLink 业务层(Spring Boot 3)。
 * 鉴权 / 持久化 / seq 分配 / 离线拉取 / 回执 / 群聊。与 connect 零代码依赖,只经 MQ+Redis 通信。
 */
@SpringBootApplication
@EnableScheduling // 下行发件箱扫描器(OutboxService,5s 一轮)
public class ChatApplication {
    private static final Logger log = LoggerFactory.getLogger(ChatApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
        log.info("QuantumLink im-chat 启动");
    }
}
