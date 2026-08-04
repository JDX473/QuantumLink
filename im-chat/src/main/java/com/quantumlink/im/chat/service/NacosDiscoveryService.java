package com.quantumlink.im.chat.service;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Properties;

/**
 * Nacos 服务发现:查询在线 connect 节点清单。
 *
 * <p>职责:connect 节点启动时注册到 Nacos 服务 {@code im-connect}(Nacos 客户端
 * 自动维护心跳 + 健康检查),本服务查询健康实例清单,配合 Redis 连接数做
 * <b>最少连接</b>调度。节点清单由注册中心动态感知 —— 加节点/节点宕机
 * 都不需要手动改配置。
 *
 * <p>与 {@code connect.nodes} 静态配置的关系:此前节点列表写死在 application.yml,
 * 现在由 Nacos 托管(服务发现的本质职责),两者不可混用。
 */
@Slf4j
@Component
public class NacosDiscoveryService {

    /** connect 在 Nacos 中注册的服务名(与 connect NodeReporter 一致) */
    public static final String CONNECT_SERVICE = "im-connect";

    @Value("${nacos.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    private NamingService namingService;

    @PostConstruct
    public void init() {
        try {
            Properties props = new Properties();
            props.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
            this.namingService = NamingFactory.createNamingService(props);
            log.info("nacos discovery started: server={} service={}", serverAddr, CONNECT_SERVICE);
        } catch (Exception e) {
            throw new IllegalStateException("init nacos naming service failed: " + serverAddr, e);
        }
    }

    /** 查询 im-connect 健康实例列表(空 = 无可用节点) */
    public List<Instance> listHealthyInstances() {
        try {
            return namingService.getAllInstances(CONNECT_SERVICE, true);
        } catch (Exception e) {
            log.error("list connect instances failed", e);
            return List.of();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (namingService != null) {
            try {
                namingService.shutDown();
                log.info("nacos discovery shut down");
            } catch (Exception e) {
                log.warn("shutdown nacos naming service failed", e);
            }
        }
    }
}
