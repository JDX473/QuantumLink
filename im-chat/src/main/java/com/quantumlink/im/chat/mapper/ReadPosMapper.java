package com.quantumlink.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quantumlink.im.chat.entity.ReadPos;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 已读位点 Mapper。
 *
 * <p>水位写入用 UPSERT + GREATEST:多 chat 实例并发上报同会话时,
 * 只推进不回退(MySQL 8.0.19+ 的 AS new 语法,取两值较大者)。
 */
@Mapper
public interface ReadPosMapper extends BaseMapper<ReadPos> {

    /**
     * 幂等推进水位:不存在则插入;存在则取较大值(只进不退)。
     * 用 VALUES() 兼容形式:row alias(AS new)语法下未限定的 read_seq 与别名列冲突会报 ambiguous。
     */
    @Insert("INSERT INTO im_read_pos (user_id, conversation_id, read_seq) " +
            "VALUES (#{userId}, #{conversationId}, #{readSeq}) " +
            "ON DUPLICATE KEY UPDATE read_seq = GREATEST(read_seq, VALUES(read_seq))")
    int upsert(@Param("userId") String userId,
               @Param("conversationId") String conversationId,
               @Param("readSeq") long readSeq);
}
