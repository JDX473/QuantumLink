package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群实体。group_id 服务端分配(群身份,群消息的接收方维度)。
 */
@Data
@TableName("im_group")
public class Group {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务端分配,群身份 */
    private String groupId;

    /** 群名 */
    private String name;

    /** 群主 userId */
    private String ownerId;

    private LocalDateTime createdAt;
}
