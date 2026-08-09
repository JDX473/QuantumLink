package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 踢人指令体(chat 发布 → Redis Pub/Sub im:kick 频道 → connect 消费)。
 *
 * <p>chat 发现某设备应被踢(同端类型新登录踢旧 / 手动踢设备)→ 把
 * {@code {userId, deviceId}} 发布到 Redis 频道;所有 connect 节点订阅并收到,
 * 各节点用"本地 ChannelManager 有没有这个连接"判断——只有持有目标连接的节点才关。
 *
 * <p>为什么用 Redis Pub/Sub 而非 MQ/RPC:KICK 是低频、尽力而为的控制消息,
 * 即发即弃的缺点被"删 token 让重连被拒"兜底(踢不到也出局);且免查会话表定位节点。
 */
@Getter
@Setter
public class KickPayload {
    /** 被踢的用户 */
    private String userId;

    /** 被踢的设备(该用户下某台设备) */
    private String deviceId;

    public KickPayload() {}

    public KickPayload(String userId, String deviceId) {
        this.userId = userId;
        this.deviceId = deviceId;
    }
}
