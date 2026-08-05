package com.quantumlink.im.connect.service;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChannelManager 嵌套 Map 测试:多端并存、并发安全、按用户维度操作。
 */
class ChannelManagerTest {

    @Test
    void multiDevice_oneUser() {
        String userId = "user-A";
        Channel pc = new EmbeddedChannel();
        Channel phone = new EmbeddedChannel();
        Channel web = new EmbeddedChannel();

        ChannelManager.add(userId, "pc", pc);
        ChannelManager.add(userId, "phone", phone);
        ChannelManager.add(userId, "web", web);

        // 同一用户多设备并存,互不覆盖
        assertSame(pc, ChannelManager.get(userId, "pc"));
        assertSame(phone, ChannelManager.get(userId, "phone"));
        assertSame(web, ChannelManager.get(userId, "web"));
        assertEquals(3, ChannelManager.deviceCount(userId));

        // 按用户取全部设备(多端推送基础)
        Collection<Channel> all = ChannelManager.getAll(userId);
        assertEquals(3, all.size());
        assertTrue(all.contains(pc) && all.contains(phone) && all.contains(web));

        ChannelManager.removeAll(userId);
        assertEquals(0, ChannelManager.deviceCount(userId));
    }

    @Test
    void differentUsers_isolated() {
        ChannelManager.add("user-A", "pc", new EmbeddedChannel());
        ChannelManager.add("user-B", "pc", new EmbeddedChannel());

        assertEquals(1, ChannelManager.deviceCount("user-A"));
        assertEquals(1, ChannelManager.deviceCount("user-B"));
        assertNull(ChannelManager.get("user-A", "phone"));

        // 不同用户即使 deviceId 相同也不冲突
        assertNotSame(ChannelManager.get("user-A", "pc"), ChannelManager.get("user-B", "pc"));
    }

    @Test
    void remove_device_then_userCleaned() {
        String userId = "user-A";
        Channel pc = new EmbeddedChannel();
        Channel phone = new EmbeddedChannel();
        ChannelManager.add(userId, "pc", pc);
        ChannelManager.add(userId, "phone", phone);

        ChannelManager.remove(userId, "pc");
        assertNull(ChannelManager.get(userId, "pc"));
        assertEquals(1, ChannelManager.deviceCount(userId));

        // 最后一个设备移除后,整条用户记录清空
        ChannelManager.remove(userId, "phone");
        assertEquals(0, ChannelManager.deviceCount(userId));
        assertTrue(ChannelManager.getAll(userId).isEmpty());
    }

    @Test
    void concurrentAdd_sameUser_noDataLoss() throws InterruptedException {
        // 100 线程并发给同一用户加 100 个设备,验证 computeIfAbsent 原子性
        String userId = "user-X";
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    ChannelManager.add(userId, "dev-" + idx, new EmbeddedChannel());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(n, ChannelManager.deviceCount(userId), "并发加设备不应丢失任何一条");
        ChannelManager.removeAll(userId);
    }

    @Test
    void size_countsAllDevicesAcrossUsers() {
        ChannelManager.add("uA", "d1", new EmbeddedChannel());
        ChannelManager.add("uA", "d2", new EmbeddedChannel());
        ChannelManager.add("uB", "d1", new EmbeddedChannel());
        assertEquals(3, ChannelManager.size());
        ChannelManager.removeAll("uA");
        ChannelManager.removeAll("uB");
        assertEquals(0, ChannelManager.size());
    }

    @Test
    void size_empty() {
        assertEquals(0, ChannelManager.size());
    }

    @Test
    void get_unknownUserOrDevice_returnsNull() {
        assertNull(ChannelManager.get("nobody", "nope"));
        ChannelManager.add("uX", "d1", new EmbeddedChannel());
        assertNull(ChannelManager.get("uX", "d2"));
        assertNull(ChannelManager.get("uY", "d1"));
        ChannelManager.removeAll("uX");
    }

    @Test
    void removeAll_unknownUser_noop() {
        ChannelManager.removeAll("nobody"); // 不应抛异常
    }

    @Test
    void remove_unknownDevice_noop() {
        ChannelManager.add("uZ", "d1", new EmbeddedChannel());
        ChannelManager.remove("uZ", "d2"); // 不存在,不应抛
        assertEquals(1, ChannelManager.deviceCount("uZ"));
        ChannelManager.removeAll("uZ");
    }

    @Test
    void add_sameDevice_overwrites() {
        ChannelManager.add("uW", "d1", new EmbeddedChannel());
        ChannelManager.add("uW", "d1", new EmbeddedChannel());
        assertEquals(1, ChannelManager.deviceCount("uW"));
        ChannelManager.removeAll("uW");
    }
}
