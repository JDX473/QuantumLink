package com.quantumlink.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quantumlink.im.chat.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 会话 Mapper。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 会话内原子自增 last_seq 并返回新值。
     *
     * <p>这是 seq 分配的可靠性锚点:UPDATE 自带行锁,同一会话的并发消息在此串行,
     * 保证 seq 单调不重复。返回影响行数,再用查询取新值(或直接查)。
     */
    @Update("UPDATE im_conversation SET last_seq = last_seq + 1 WHERE conversation_id = #{conversationId}")
    int incrementLastSeq(@Param("conversationId") String conversationId);
}
