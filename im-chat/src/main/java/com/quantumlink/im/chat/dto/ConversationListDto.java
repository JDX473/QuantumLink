package com.quantumlink.im.chat.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 会话列表响应。
 *
 * <p>左侧会话栏:每个会话显示对方用户名 + 最后一条消息 + 时间。
 * 按最后一条消息时间倒序(最近对话靠上)。
 */
@Getter
@Setter
public class ConversationListDto {

    private List<ConversationItem> conversations;

    @Getter
    @Setter
    public static class ConversationItem {
        /** 会话 ID(A#B) */
        private String conversationId;

        /** 对方 userId */
        private String peerUserId;

        /** 对方用户名(对外显示) */
        private String peerUsername;

        /** 对方头像 URL(UI 显示) */
        private String peerAvatar;

        /** 最后一条消息内容(预览) */
        private String lastMessage;

        /** 最后一条消息时间(ms) */
        private Long lastTime;

        /** 最后一条消息 seq */
        private Long lastSeq;
    }
}
