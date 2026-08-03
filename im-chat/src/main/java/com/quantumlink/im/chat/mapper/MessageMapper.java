package com.quantumlink.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quantumlink.im.chat.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 消息 Mapper。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
