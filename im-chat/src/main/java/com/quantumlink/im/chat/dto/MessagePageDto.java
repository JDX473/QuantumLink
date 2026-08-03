package com.quantumlink.im.chat.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 增量拉取消息的响应。
 *
 * <p>按 seq 升序返回消息;客户端把返回的消息按 seq 归位后,
 * 更新本会话的位点(afterSeq = 最后一条的 seq),继续拉下一页直到 hasMore=false。
 */
@Getter
@Setter
public class MessagePageDto {
    /** 按 seq 升序的消息 */
    private List<MessageItem> messages;

    /** 是否还有更多(afterSeq 之后是否还有消息) */
    private boolean hasMore;

    /** 本会话当前最大 seq(已落库水位线) */
    private Long serverMaxSeq;

    /** 消息项:下发给客户端的最小字段集 */
    @Getter
    @Setter
    public static class MessageItem {
        private Long serverMsgId;
        private Long seq;
        private String conversationId;
        private String senderId;
        private String msgType;
        private String content;
        private Long serverTime;
    }
}
