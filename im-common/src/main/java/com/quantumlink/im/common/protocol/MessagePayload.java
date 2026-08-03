package com.quantumlink.im.common.protocol;

import lombok.Getter;
import lombok.Setter;

/**
 * 消息体(客户端 ↔ 服务端,业务协议)。
 *
 * <p>上行(客户端→服务端):sender/receiver/content 由客户端填,clientMsgId 用于幂等,
 * serverMsgId/seq 由服务端填。
 * 下行(服务端→接收方):服务端填 serverMsgId + seq。
 */
@Getter
@Setter
public class MessagePayload {
    /** 客户端生成(clientMsgId = deviceId + 自增),上行幂等去重键 */
    private String clientMsgId;

    /** 服务端生成的会话 ID(min(a,b)#max(a,b)) */
    private String conversationId;

    private String senderId;
    private String receiverId;

    /** 发送者用户名(下行填充,UI 显示用,不暴露 userId) */
    private String senderName;

    /** 发送者头像 URL(下行填充,UI 显示用) */
    private String senderAvatar;

    /** 消息类型: TEXT / IMAGE ... */
    private String msgType;

    private String content;

    /** 服务端落库时生成的消息正式身份(下行带回) */
    private Long serverMsgId;

    /** 服务端生成的会话内单调序号(下行带回) */
    private Long seq;

    /** 客户端发送时间戳(ms) */
    private Long clientTime;

    /** 服务端时间戳(ms) */
    private Long serverTime;
}
