package com.quantumlink.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 群消息实体:与单聊 im_message 分离(分库分表时群按 group_id 分片)。
 *
 * <p>与单聊消息的差异:
 * <ul>
 *   <li>receiver 维度是群(group_id),不是用户;</li>
 *   <li>群消息不回 DELIVER(无"对方已送达"),status 恒 SENT;</li>
 *   <li>seq 是群维度 Redis INCR(im:group_seq:{groupId}),群内单调。</li>
 * </ul>
 */
@Data
@TableName("im_group_message")
public class GroupMessage {
    /** 主键 = server_msg_id。用雪花(ASSIGN_ID):多 chat 实例写消息需全局唯一 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 客户端生成,幂等去重键 */
    private String clientMsgId;

    private String groupId;

    private String senderId;

    private String msgType;

    private String content;

    /** 群内单调递增(服务端分配) */
    private Long seq;

    private String status;

    private Long serverTime;
}
