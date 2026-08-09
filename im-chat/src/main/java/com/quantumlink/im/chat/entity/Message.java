package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息实体。主键 = server_msg_id,应用雪花(ASSIGN_ID)生成(多 chat 实例全局唯一);
 * client_msg_id 客户端生成 UUID(幂等去重键)。
 */
@Data
@TableName("im_message")
public class Message {
    /** 主键 = server_msg_id,用雪花(ASSIGN_ID):多 chat 实例写消息需全局唯一,DB 自增会撞 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 客户端生成(UUID),幂等去重键 */
    private String clientMsgId;

    /** 会话 ID:A#B */
    private String conversationId;

    private String senderId;
    private String receiverId;

    private String msgType;
    private String content;

    /** 会话内单调序号(业务层 Redis INCR 取号) */
    private Long seq;

    /** SENT / DELIVERED */
    private String status;

    /** 服务端时间戳(ms) */
    private Long serverTime;
}
