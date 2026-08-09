package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话实体。注意:last_seq 是**遗留字段**——seq 已改 Redis INCR({@code im:conv:seq:{conv}})
 * 业务层取号,本表无代码写入,仅保留表结构。
 */
@Data
@TableName("im_conversation")
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID:A#B */
    private String conversationId;

    /** 遗留字段:seq 已改 Redis INCR 取号,当前无人写入(保留表结构) */
    private Long lastSeq;

    private String lastMsgId;
    private java.time.LocalDateTime lastMsgTime;
}
