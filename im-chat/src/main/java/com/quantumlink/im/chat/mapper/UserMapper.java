package com.quantumlink.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quantumlink.im.chat.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
