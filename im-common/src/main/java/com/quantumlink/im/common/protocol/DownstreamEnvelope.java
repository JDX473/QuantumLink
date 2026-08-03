package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 下行信封:chat → connect({@code server2client})的统一消息结构。
 *
 * <p>connect 消费时只解析这一个信封,就能知道"推给谁、是什么类型"。
 * 后续新增下行类型(如 DELIVER 回执)只需扩展 contentType,无需改 connect 解析逻辑。
 */
@Getter
@Setter
public class DownstreamEnvelope {
    /** 推给哪个用户(connect 靠它定位 Channel) */
    private String targetUserId;

    /** 目标设备;为空 = 推给该用户所有在线设备(多端全推) */
    private String targetDeviceId;

    /** 内容类型:ACK(回执)/ MSG(消息) */
    private String contentType;

    /** 实际内容 JSON(AckPayload 或 MessagePayload 的序列化) */
    private String bodyJson;

    /** 内容类型常量 */
    public static final String TYPE_ACK = "ACK";
    public static final String TYPE_MSG = "MSG";
}
