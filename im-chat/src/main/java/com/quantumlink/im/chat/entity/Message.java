package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息实体。主键自增即 server_msg_id;client_msg_id 客户端生成(幂等去重键)。
 */
@Data
@TableName("im_message")
public class Message {
    /** 主键自增,即 server_msg_id */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户端生成(device_id+自增),幂等去重键 */
    private String clientMsgId;

    /** 会话 ID:A#B */
    private String conversationId;

    private String senderId;
    private String receiverId;

    private String msgType;
    private String content;

    /** 会话内单调序号(事务内原子自增) */
    private Long seq;

    /** SENT / DELIVERED */
    private String status;

    /** 服务端时间戳(ms) */
    private Long serverTime;
}
