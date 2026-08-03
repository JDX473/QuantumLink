package com.quantumlink.im.loadtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 压测客户端(骨架,MVP 后置)。
 * Phase 3 实现:Netty TCP client,模拟 N 用户收发,统计端到端 RT/吞吐/错误率。
 */
public class LoadTestClient {
    private static final Logger log = LoggerFactory.getLogger(LoadTestClient.class);

    public static void main(String[] args) {
        log.info("QuantumLink im-loadtest 骨架(Phase 0),MVP 后置实现");
    }
}
