package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.entity.GroupMessage;
import com.quantumlink.im.chat.entity.ReadPos;
import com.quantumlink.im.chat.mapper.GroupMemberMapper;
import com.quantumlink.im.chat.mapper.GroupMessageMapper;
import com.quantumlink.im.chat.mapper.ReadPosMapper;
import com.quantumlink.im.chat.mq.DownstreamProducer;
import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.protocol.ReadReportPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 已读水位:单聊(对端水位单调推进 + 持久化 + 推 READ)+ 群聊(成员水位 + 预聚合计数,
 * 不群广播,实时只推给受影响消息的发送者)。
 */
class ReadServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ReadPosMapper readPosMapper;
    private DownstreamProducer downstreamProducer;
    private GroupMemberMapper groupMemberMapper;
    private GroupMessageMapper groupMessageMapper;
    private ReadService readService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        readPosMapper = mock(ReadPosMapper.class);
        downstreamProducer = mock(DownstreamProducer.class);
        groupMemberMapper = mock(GroupMemberMapper.class);
        groupMessageMapper = mock(GroupMessageMapper.class);
        readService = new ReadService(redisTemplate, readPosMapper, downstreamProducer, groupMemberMapper, groupMessageMapper);
    }

    private ReadReportPayload report(String conv, String reader, long untilSeq) {
        ReadReportPayload r = new ReadReportPayload();
        r.setConversationId(conv);
        r.setReaderId(reader);
        r.setUntilSeq(untilSeq);
        return r;
    }

    @Test
    void handleReadReport_advance_persistsAndPushesToPeer() {
        // Lua 推进成功(返回 1)
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L);

        readService.handleReadReport(report("u_a#u_b", "u_b", 10));

        // 持久化水位
        verify(readPosMapper).upsert("u_b", "u_a#u_b", 10);
        // 推 READ 事件给对端 u_a(readerId=u_b,水位 10)
        verify(downstreamProducer).sendEnvelope(eq("u_a"), isNull(),
                eq(DownstreamEnvelope.TYPE_READ), argThat(d -> {
                    ReadReportPayload evt = (ReadReportPayload) d;
                    return "u_a#u_b".equals(evt.getConversationId())
                            && "u_b".equals(evt.getReaderId())
                            && evt.getUntilSeq() == 10;
                }));
    }

    @Test
    void handleReadReport_regression_ignored() {
        // Lua 返回 0(乱序/回退,未推进)
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(0L);

        readService.handleReadReport(report("u_a#u_b", "u_b", 10));

        verify(readPosMapper, never()).upsert(anyString(), anyString(), anyLong());
        verify(downstreamProducer, never()).sendEnvelope(anyString(), any(), anyString(), any());
    }

    @Test
    void handleReadReport_nonParticipant_ignored() {
        // reader 不在会话里 → 不执行 Lua(Redis execute 不调用)
        readService.handleReadReport(report("u_a#u_b", "u_x", 10));
        verify(redisTemplate, never()).execute(any(), anyList(), any(), any());
        verify(readPosMapper, never()).upsert(anyString(), anyString(), anyLong());
    }

    @Test
    void handleReadReport_badInput_ignored() {
        readService.handleReadReport(report(null, "u_b", 10));
        readService.handleReadReport(report("u_a#u_b", null, 10));
        readService.handleReadReport(report("u_a#u_b", "u_b", 0));
        verify(redisTemplate, never()).execute(any(), anyList(), any(), any());
    }

    @Test
    void readSeqOf_redisHit() {
        when(valueOps.get("im:read:u_a#u_b:u_b")).thenReturn("42");
        assertEquals(42, readService.readSeqOf("u_a#u_b", "u_b"));
    }

    @Test
    void readSeqOf_redisMiss_dbFallback() {
        when(valueOps.get("im:read:u_a#u_b:u_b")).thenReturn(null);
        ReadPos pos = new ReadPos();
        pos.setUserId("u_b");
        pos.setConversationId("u_a#u_b");
        pos.setReadSeq(30L);
        when(readPosMapper.selectOne(any())).thenReturn(pos);
        assertEquals(30, readService.readSeqOf("u_a#u_b", "u_b"));
    }

    @Test
    void readSeqOf_nothing_returnsZero() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(readPosMapper.selectOne(any())).thenReturn(null);
        assertEquals(0, readService.readSeqOf("u_a#u_b", "u_b"));
    }

    @Test
    void peerReadSeq_returnsOtherSide() {
        // 我在 u_a,查对端 u_b 的水位
        when(valueOps.get("im:read:u_a#u_b:u_b")).thenReturn("7");
        assertEquals(7, readService.peerReadSeq("u_a#u_b", "u_a"));
    }

    @Test
    void peerReadSeq_notParticipant_returnsZero() {
        assertEquals(0, readService.peerReadSeq("u_a#u_b", "u_x"));
        assertEquals(0, readService.peerReadSeq(null, "u_a"));
    }

    // ==================== 群已读 ====================

    @Test
    void groupRead_advance_countsAndNoPush() {
        // 成员校验通过
        when(groupMemberMapper.selectCount(any())).thenReturn(1L);
        // 群 Lua 推进成功(返回旧水位 5,推进到 10)
        when(redisTemplate.execute(any(), anyList(), any(), any(), any())).thenReturn(5L);

        readService.handleReadReport(report("g_xxx", "u_b", 10));

        // 用的是群水位 key(im:group_read:{gid}:{member})
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<String>> keys = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(redisTemplate).execute(any(), keys.capture(), any(), any(), any());
        assertEquals(java.util.List.of("im:group_read:g_xxx:u_b"), keys.getValue());
        // 群已读不落 MySQL、不推单聊 READ 事件(群不广播);无受影响消息(selectList 空)→ 也不推 GROUP_READ
        verify(readPosMapper, never()).upsert(anyString(), anyString(), anyLong());
        verify(downstreamProducer, never()).sendEnvelope(anyString(), any(), anyString(), any());
    }

    @Test
    void groupRead_advance_pushesOnlyToSenders() {
        when(groupMemberMapper.selectCount(any())).thenReturn(1L);
        when(redisTemplate.execute(any(), anyList(), any(), any(), any())).thenReturn(5L); // 旧水位 5 → 10
        // 区间 (5,10] 有两条消息,发送者都是 u_a
        GroupMessage m1 = new GroupMessage();
        m1.setSenderId("u_a"); m1.setSeq(6L);
        GroupMessage m2 = new GroupMessage();
        m2.setSenderId("u_a"); m2.setSeq(8L);
        when(groupMessageMapper.selectList(any())).thenReturn(java.util.List.of(m1, m2));
        when(valueOps.get("im:group_msg_read:g_xxx:6")).thenReturn("3");
        when(valueOps.get("im:group_msg_read:g_xxx:8")).thenReturn("3");

        readService.handleReadReport(report("g_xxx", "u_b", 10));

        // 推给发送者 u_a(不是读者 u_b),数据含 conversationId/seq/readCount
        verify(downstreamProducer, times(2)).sendEnvelope(eq("u_a"), isNull(),
                eq(DownstreamEnvelope.TYPE_GROUP_READ), argThat(d -> {
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) d;
                    return "g_xxx".equals(map.get("conversationId"))
                            && Integer.valueOf(3).equals(map.get("readCount"));
                }));
        // 不推给读者 u_b,不推单聊 READ
        verify(downstreamProducer, never()).sendEnvelope(eq("u_b"), any(), anyString(), any());
    }

    @Test
    void groupRead_largeRange_skipsPush() {
        when(groupMemberMapper.selectCount(any())).thenReturn(1L);
        // 旧水位 0 → 100(区间 100 > MAX_LIVE_PUSH_RANGE=20)→ 不查发送者、不推
        when(redisTemplate.execute(any(), anyList(), any(), any(), any())).thenReturn(0L);
        readService.handleReadReport(report("g_xxx", "u_b", 100));
        verify(groupMessageMapper, never()).selectList(any());
        verify(downstreamProducer, never()).sendEnvelope(anyString(), any(), eq(DownstreamEnvelope.TYPE_GROUP_READ), any());
    }

    @Test
    void groupRead_regression_ignored() {
        when(groupMemberMapper.selectCount(any())).thenReturn(1L);
        when(redisTemplate.execute(any(), anyList(), any(), any(), any())).thenReturn(-1L); // 水位未推进

        readService.handleReadReport(report("g_xxx", "u_b", 10));

        verify(groupMessageMapper, never()).selectList(any());
        verify(downstreamProducer, never()).sendEnvelope(anyString(), any(), anyString(), any());
    }

    @Test
    void groupRead_nonMember_ignored() {
        when(groupMemberMapper.selectCount(any())).thenReturn(0L); // 非成员

        readService.handleReadReport(report("g_xxx", "u_x", 10));

        verify(redisTemplate, never()).execute(any(), anyList(), any(), any(), any());
    }

    @Test
    void singleChatRead_doesNotTouchGroupMember() {
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L); // 单聊 Lua
        readService.handleReadReport(report("u_a#u_b", "u_b", 10));
        verify(groupMemberMapper, never()).selectCount(any());
    }
}
