package com.quantumlink.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quantumlink.im.chat.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会话 Mapper。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 会话内原子分配新 seq。
     *
     * <p>这是 seq 分配的可靠性锚点:
     * <ol>
     *   <li>SELECT ... FOR UPDATE 锁住该会话行,同一会话的并发消息在此串行;</li>
     *   <li>读当前 last_seq,+1 得到新 seq;</li>
     *   <li>UPDATE 写回。</li>
     * </ol>
     *
     * <p>为什么不用 {@code UPDATE last_seq = last_seq + 1} 再查:UPDATE 只返回影响行数,
     * 拿不到自增后的新值;再查会读到别的并发事务提交后的值(可能跳过序号)。
     * FOR UPDATE 锁行后读到的 last_seq 是串行后的准确值,新 seq 单调且不跳号。
     */
    @Select("SELECT id, conversation_id, last_seq, last_msg_id, last_msg_time, created_at, updated_at " +
            "FROM im_conversation WHERE conversation_id = #{conversationId} FOR UPDATE")
    Conversation selectForUpdate(@Param("conversationId") String conversationId);

    @Update("UPDATE im_conversation SET last_seq = #{newSeq} WHERE conversation_id = #{conversationId}")
    int updateLastSeq(@Param("conversationId") String conversationId, @Param("newSeq") long newSeq);
}
