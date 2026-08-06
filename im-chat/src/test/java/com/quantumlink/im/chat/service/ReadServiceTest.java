package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.entity.ReadPos;
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
 * 已读水位:单调推进(只进不退)、持久化、推 READ 事件给对端、对端水位查询。
 */
class ReadServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ReadPosMapper readPosMapper;
    private DownstreamProducer downstreamProducer;
    private ReadService readService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        readPosMapper = mock(ReadPosMapper.class);
        downstreamProducer = mock(DownstreamProducer.class);
        readService = new ReadService(redisTemplate, readPosMapper, downstreamProducer);
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
}
