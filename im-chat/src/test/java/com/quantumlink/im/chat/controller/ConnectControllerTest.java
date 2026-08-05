package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.service.NacosDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectControllerTest {

    private NacosDiscoveryService discovery;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ConnectController controller;

    @BeforeEach
    void setUp() {
        discovery = mock(NacosDiscoveryService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        controller = new ConnectController(discovery, redisTemplate);
    }

    private com.alibaba.nacos.api.naming.pojo.Instance inst(String ip, int port) {
        com.alibaba.nacos.api.naming.pojo.Instance i = new com.alibaba.nacos.api.naming.pojo.Instance();
        i.setIp(ip);
        i.setPort(port);
        return i;
    }

    @Test
    void connects_noInstances_returnsFalse() {
        when(discovery.listHealthyInstances()).thenReturn(List.of());
        Map<String, Object> resp = controller.connects();
        assertEquals(false, resp.get("success"));
    }

    @Test
    void connects_picksLeastConnections() {
        when(discovery.listHealthyInstances()).thenReturn(List.of(inst("127.0.0.1", 19001), inst("127.0.0.1", 19002)));
        when(valueOps.get("im:node:conns:127.0.0.1:19001")).thenReturn("5");
        when(valueOps.get("im:node:conns:127.0.0.1:19002")).thenReturn("2");

        Map<String, Object> resp = controller.connects();
        assertEquals(true, resp.get("success"));
        assertEquals("127.0.0.1:19002", resp.get("address"));
        assertEquals(2, resp.get("connections"));
    }

    @Test
    void connects_missingConnCount_treatsAsZero() {
        when(discovery.listHealthyInstances()).thenReturn(List.of(inst("127.0.0.1", 19001), inst("127.0.0.1", 19002)));
        when(valueOps.get("im:node:conns:127.0.0.1:19001")).thenReturn("10");
        when(valueOps.get("im:node:conns:127.0.0.1:19002")).thenReturn(null); // 无记录 → 0

        Map<String, Object> resp = controller.connects();
        assertEquals("127.0.0.1:19002", resp.get("address"));
        assertEquals(0, resp.get("connections"));
    }
}
