package com.quantumlink.im.chat.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 群消息增量拉取的响应(与单聊 MessagePageDto 同构)。
 *
 * <p>按 seq 升序返回;hasMore 支持分页(打开群加载尾部、向上翻查更早历史)。
 */
@Getter
@Setter
public class GroupMessagePageDto {
    /** 按 seq 升序的消息 */
    private List<GroupMessageItemDto> messages;

    /** 是否还有更多(afterSeq 之后是否还有消息) */
    private boolean hasMore;

    /** 群当前最大 seq(水位线,尾部加载用) */
    private Long maxSeq;
}
