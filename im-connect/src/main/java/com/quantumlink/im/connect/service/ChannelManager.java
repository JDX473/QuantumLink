package com.quantumlink.im.connect.service;

import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 Channel 管理器:userId#deviceId → Channel。
 *
 * <p>职责:管理本节点上的长连接,供下行推送时查 Channel。
 * <ul>
 *   <li>Key = userId + "#" + deviceId(MVP 单节点,仍带设备维度为多端扩展)</li>
 *   <li>每设备一个连接</li>
 * </ul>
 */
public final class ChannelManager {
    private static final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();

    private ChannelManager() {}

    public static String key(String userId, String deviceId) {
        return userId + "#" + deviceId;
    }

    public static void add(String userId, String deviceId, Channel channel) {
        channels.put(key(userId, deviceId), channel);
    }

    public static Channel get(String userId, String deviceId) {
        return channels.get(key(userId, deviceId));
    }

    public static Channel get(String key) {
        return channels.get(key);
    }

    public static void remove(String userId, String deviceId) {
        channels.remove(key(userId, deviceId));
    }

    public static int size() {
        return channels.size();
    }
}
