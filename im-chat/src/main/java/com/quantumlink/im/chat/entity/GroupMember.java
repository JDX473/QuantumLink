package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群成员实体:群 → 成员 多对多。
 */
@Data
@TableName("im_group_member")
public class GroupMember {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String groupId;

    private String userId;

    /** OWNER / MEMBER */
    private String role;

    private LocalDateTime joinedAt;
}
