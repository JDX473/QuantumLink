package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 下行信封:chat → connect({@code server2client})的统一消息结构。
 *
 * <p>结构:{@code {type, to, targets, data}}。
 * <ul>
 *   <li>顶层字段是"投递元数据"——connect 只解析它们,决定推给谁;</li>
 *   <li>data 是"内容"——connect 不解析其内部,序列化后原样包进客户端帧。</li>
 * </ul>
 *
 * <p>单播与群播:<b>to 与 targets 互斥</b>。
 * <ul>
 *   <li>{@code to} 非空 = 单播(单聊/回执):推给该用户所有在线设备;</li>
 *   <li>{@code targets} 非空 = 群播(群消息):按节点聚合后的目标成员 userId 列表,
 *       connect 遍历推送 —— 每个节点一条 MQ 推给该节点上的所有目标成员,
 *       大幅减少 MQ 扇出(100 人群 2 节点 = 2 条而非 100 条)。</li>
 * </ul>
 *
 * <p>为什么信封而不是平铺:connect 与 chat 是两层解耦服务。connect 只负责投递,
 * 不需要认识 AckPayload / MessagePayload 的内部字段;新增下行类型只需扩展 data,
 * 顶层 type/to 不变,connect 无需改动。data 用嵌套对象(非字符串)避免双重转义。
 */
@Getter
@Setter
public class DownstreamEnvelope {
    /** 推给哪个用户(单播;connect 靠它定位 Channel)。群播时为空 */
    private String to;

    /** 目标设备;为空 = 推给该用户所有在线设备(多端全推) */
    private String deviceId;

    /** 群播:该节点上的目标成员 userId 列表(connect 遍历推送)。单播时为空 */
    private List<String> targets;

    /** 内容类型:ACK(回执)/ MSG(消息) */
    private String type;

    /** 实际内容(AckPayload 或 MessagePayload 对象) */
    private Object data;

    /** 内容类型常量 */
    public static final String TYPE_ACK = "ACK";
    public static final String TYPE_MSG = "MSG";
    /** 已读事件(对端读了本会话的消息,水位推进后推给发送方) */
    public static final String TYPE_READ = "READ";
}
