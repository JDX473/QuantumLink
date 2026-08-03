package com.quantumlink.im.connect.service;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * 连接上下文:把 userId / deviceId 绑到 Channel 上,
 * 供握手、心跳、断连清理、下行推送时读取。
 */
public final class ConnectionContext {
    public static final AttributeKey<String> USER_ID = AttributeKey.valueOf("userId");
    public static final AttributeKey<String> DEVICE_ID = AttributeKey.valueOf("deviceId");
    public static final AttributeKey<Boolean> AUTHENTICATED = AttributeKey.valueOf("authenticated");

    private ConnectionContext() {}

    public static void bind(Channel channel, String userId, String deviceId) {
        channel.attr(USER_ID).set(userId);
        channel.attr(DEVICE_ID).set(deviceId);
        channel.attr(AUTHENTICATED).set(Boolean.TRUE);
    }

    public static String userId(Channel channel) {
        return channel.attr(USER_ID).get();
    }

    public static String deviceId(Channel channel) {
        return channel.attr(DEVICE_ID).get();
    }

    public static boolean authenticated(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(AUTHENTICATED).get());
    }

    public static void clear(Channel channel) {
        channel.attr(USER_ID).set(null);
        channel.attr(DEVICE_ID).set(null);
        channel.attr(AUTHENTICATED).set(null);
    }
}
