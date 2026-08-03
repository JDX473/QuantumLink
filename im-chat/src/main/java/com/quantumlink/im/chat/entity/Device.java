package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备实体。device_id 服务端分配(区分客户端 / 多端同步基础)。
 */
@Data
@TableName("im_device")
public class Device {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务端分配,设备身份 */
    private String deviceId;

    private String userId;

    /** web / desktop / mobile */
    private String deviceType;

    /** 该设备登录凭证 */
    private String token;

    /** token 过期时间(ms) */
    private Long tokenExpire;

    private LocalDateTime lastActiveAt;

    private LocalDateTime createdAt;
}
