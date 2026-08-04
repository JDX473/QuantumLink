package com.quantumlink.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quantumlink.im.chat.entity.GroupMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 群消息 Mapper。
 */
@Mapper
public interface GroupMessageMapper extends BaseMapper<GroupMessage> {
}
