package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 已读上报/已读事件体(双向复用)。
 *
 * <p><b>上行(客户端 → 服务端,READ_ACK 帧)</b>:接收方 B 打开会话/看到新消息时,
 * 上报"我已读到 seq X"——{@code conversationId + untilSeq};connect 转发时补
 * {@code readerId}(谁上报的,从连接上下文取,不信任客户端)。
 *
 * <p><b>下行(服务端 → 发送方,DownstreamEnvelope TYPE_READ)</b>:chat 推进水位后,
 * 把 {@code {conversationId, readerId, untilSeq}} 推给对端 A,A 据此渲染"对方已读"。
 *
 * <p>为什么"已读"用水位而非逐条标记:seq 会话内单调递增,一条水位"读到 seq X"
 * 等价于"≤X 全部已读",O(1) 表达,无需逐条状态。
 */
@Getter
@Setter
public class ReadReportPayload {
    /** 哪个会话 */
    private String conversationId;

    /** 已读水位:读到哪条 seq(≤ 该值全部已读) */
    private Long untilSeq;

    /** 谁读了(connect 上行转发时从连接上下文填;下行事件里是读者 userId) */
    private String readerId;
}
