package com.quantumlink.im.chat.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 群消息响应项:下发给客户端的最小字段集(含发送者资料,UI 显示用)。
 */
@Getter
@Setter
public class GroupMessageItemDto {
    private String serverMsgId;
    private Long seq;
    private String groupId;
    private String senderId;
    /** 发送者用户名 + 头像(UI 显示,与单聊 MessagePageDto 一致) */
    private String senderName;
    private String senderAvatar;
    private String msgType;
    private String content;
    private Long serverTime;
    private String status;
}
