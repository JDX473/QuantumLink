package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体。user_id 服务端分配(用户身份)。
 */
@Data
@TableName("im_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务端分配,用户身份 */
    private String userId;

    /** 登录名(唯一) */
    private String username;

    /** 密码哈希(不存明文) */
    private String passwordHash;

    private LocalDateTime createdAt;
}
