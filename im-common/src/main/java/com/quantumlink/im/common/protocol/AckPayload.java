package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * ACK 回执体(服务端 → 发送方客户端)。
 *
 * <p>STORE:chat 落库成功后回,带 serverMsgId + seq,证明消息已安全入库。
 * DELIVER:接收方已收到消息后回,证明对方已送达。
 */
@Getter
@Setter
public class AckPayload {
    /** 回执类型: STORE / DELIVER */
    private AckType ackType;

    /** 客户端生成的幂等键——客户端用它匹配"这条 ACK 对应我发的哪条消息" */
    private String clientMsgId;

    /** 引用哪条消息(serverMsgId) */
    private Long serverMsgId;

    /** 该消息的会话内序号 */
    private Long seq;

    /** 消息接收方(对方) */
    private String receiverId;

    /** 回执的会话 ID */
    private String conversationId;
}
