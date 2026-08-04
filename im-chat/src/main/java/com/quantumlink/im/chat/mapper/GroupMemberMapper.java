package com.quantumlink.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quantumlink.im.chat.entity.GroupMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 群成员 Mapper。
 */
@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {
}
