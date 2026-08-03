package com.quantumlink.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quantumlink.im.chat.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 消息 Mapper。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /** 更新消息状态:SENT → DELIVERED(接收方已收到) */
    @Update("UPDATE im_message SET status = 'DELIVERED' WHERE id = #{id} AND status = 'SENT'")
    int markDelivered(@Param("id") Long id);
}
