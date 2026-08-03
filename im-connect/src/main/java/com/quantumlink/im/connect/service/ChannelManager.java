package com.quantumlink.im.connect.service;

import io.netty.channel.Channel;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 Channel 管理器:userId → Map&lt;deviceId, Channel&gt;。
 *
 * <p>IM 是天然的一对多:一个用户可能多端在线(手机/电脑/web)。用嵌套 Map 建模
 * "用户 → 设备 → 连接",语义清晰,且按用户维度操作(踢全部设备/统计在线数)很自然。
 *
 * <p>并发安全:内层 Map 用 {@link ConcurrentHashMap},新增设备用
 * {@link ConcurrentHashMap#computeIfAbsent}(原子,避免两个线程同时 put 互相覆盖)。
 *
 * <p>仅服务本节点内存;跨节点的"用户连在哪台机器"由 Redis SessionRegistry 维护。
 */
public final class ChannelManager {
    /** userId → (deviceId → Channel) */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Channel>> channels =
            new ConcurrentHashMap<>();

    private ChannelManager() {}

    /** 新增/覆盖设备连接。computeIfAbsent 原子:并发给同一用户加设备不会互相覆盖。 */
    public static void add(String userId, String deviceId, Channel channel) {
        channels.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(deviceId, channel);
    }

    /** 查某用户某设备的连接 */
    public static Channel get(String userId, String deviceId) {
        Map<String, Channel> devices = channels.get(userId);
        return devices == null ? null : devices.get(deviceId);
    }

    /** 查某用户所有在线设备的连接(多端推送/统计用) */
    public static Collection<Channel> getAll(String userId) {
        Map<String, Channel> devices = channels.get(userId);
        return devices == null ? java.util.Collections.emptyList() : devices.values();
    }

    /** 某用户在线设备数 */
    public static int deviceCount(String userId) {
        Map<String, Channel> devices = channels.get(userId);
        return devices == null ? 0 : devices.size();
    }

    /** 移除某用户某设备的连接;若该用户无剩余设备则清空整条用户记录 */
    public static void remove(String userId, String deviceId) {
        Map<String, Channel> devices = channels.get(userId);
        if (devices == null) {
            return;
        }
        devices.remove(deviceId);
        if (devices.isEmpty()) {
            channels.remove(userId, devices);
        }
    }

    /** 移除某用户的全部设备连接(踢全部设备下线) */
    public static void removeAll(String userId) {
        channels.remove(userId);
    }

    /** 本地总连接数 */
    public static int size() {
        int count = 0;
        for (Map<String, Channel> devices : channels.values()) {
            count += devices.size();
        }
        return count;
    }
}
