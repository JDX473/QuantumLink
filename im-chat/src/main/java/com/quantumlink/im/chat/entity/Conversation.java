package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话实体。last_seq 事务内原子自增,是 seq 分配的可靠性锚点。
 */
@Data
@TableName("im_conversation")
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID:A#B */
    private String conversationId;

    /** 事务内原子自增,当前最大 seq */
    private Long lastSeq;

    private String lastMsgId;
    private java.time.LocalDateTime lastMsgTime;
}
