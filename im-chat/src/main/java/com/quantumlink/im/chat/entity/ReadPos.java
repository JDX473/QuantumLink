package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话已读位点实体:每个用户每会话一行(读的人 → 会话 → 已读水位)。
 *
 * <p>已读 = 派生状态:发送方用"对端水位"推导自己消息的已读(seq ≤ 对端水位),
 * 不写共享的 im_message 行(A#B 共享一行,逐条标已读分不清方向)。
 */
@Data
@TableName("im_read_pos")
public class ReadPos {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 谁读了(读者) */
    private String userId;

    /** 会话 ID(A#B,规范化) */
    private String conversationId;

    /** 已读水位:读到 seq X = ≤X 全部已读 */
    private Long readSeq;

    private LocalDateTime updatedAt;
}
