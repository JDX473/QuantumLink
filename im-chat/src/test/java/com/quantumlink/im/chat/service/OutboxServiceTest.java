package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.entity.Message;
import com.quantumlink.im.chat.mapper.MessageMapper;
import com.quantumlink.im.chat.mq.DownstreamProducer;
import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.protocol.MessagePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下行发件箱单测:入箱/出箱/扫描器各分支(清理/离线保留/重推退避/放弃兜底)。
 * 覆盖"不删离线条目"(位置越过丢 seq 时补拉够不到,必须等上线重推)这一核心语义。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxServiceTest {

    private static final String OUTBOX = "im:push:outbox";
    private static final String META = "im:push:outbox:meta";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private DownstreamProducer downstreamProducer;
    @Mock
    private UserCacheService userCacheService;
    @Mock
    private ZSetOperations<String, String> zsetOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        outboxService = new OutboxService(redisTemplate, messageMapper, downstreamProducer, userCacheService);
    }

    private Message sentMessage() {
        Message m = new Message();
        m.setId(123L);
        m.setSenderId("A");
        m.setReceiverId("B");
        m.setConversationId("A#B");
        m.setContent("hi");
        m.setSeq(7L);
        m.setStatus("SENT");
        m.setServerTime(System.currentTimeMillis());
        return m;
    }

    // ---------- add / remove ----------

    @Test
    void add_putsZsetWithFirstCheckScore_andMetaBorn() {
        outboxService.add(123L);
        verify(zsetOps).add(eq(OUTBOX), eq("123"), anyDouble()); // score = now + 10s
        verify(hashOps).putIfAbsent(eq(META), eq("123"), anyString()); // "0,bornMs"
    }

    @Test
    void remove_deletesZsetAndMeta() {
        outboxService.remove(123L);
        verify(zsetOps).remove(OUTBOX, "123");
        verify(hashOps).delete(META, "123");
    }

    // ---------- scan ----------

    @Test
    void scan_emptyBox_noop() {
        when(zsetOps.popMin(OUTBOX, 1L)).thenReturn(Collections.emptySet());
        outboxService.scan();
        verifyNoInteractions(messageMapper, downstreamProducer);
    }

    @Test
    void scan_futureScore_putsBackAndStops() {
        double future = System.currentTimeMillis() + 60_000;
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(new DefaultTypedTuple<>("123", future)));
        outboxService.scan();
        verify(zsetOps).add(OUTBOX, "123", future);
        verifyNoInteractions(messageMapper, downstreamProducer);
    }

    @Test
    void scan_expiredButDelivered_cleans() {
        Message m = sentMessage();
        m.setStatus("DELIVERED");
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(expiredTuple("123")), Collections.emptySet());
        when(messageMapper.selectById(123L)).thenReturn(m);
        outboxService.scan();
        verify(zsetOps).remove(OUTBOX, "123");
        verify(hashOps).delete(META, "123");
        verify(downstreamProducer, never()).sendEnvelope(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void scan_expiredButMessageGone_cleans() {
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(expiredTuple("123")), Collections.emptySet());
        when(messageMapper.selectById(123L)).thenReturn(null);
        outboxService.scan();
        verify(zsetOps).remove(OUTBOX, "123");
        verify(hashOps).delete(META, "123");
    }

    @Test
    void scan_receiverOffline_keepsEntryWithoutCountingAttempt() {
        Message m = sentMessage();
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(expiredTuple("123")), Collections.emptySet());
        when(messageMapper.selectById(123L)).thenReturn(m);
        when(hashOps.get(META, "123")).thenReturn("0," + System.currentTimeMillis());
        when(downstreamProducer.isOnline("B")).thenReturn(false);
        outboxService.scan();
        // 离线:不删、不计数、不重推,30s 后复查
        verify(zsetOps).add(eq(OUTBOX), eq("123"), anyDouble());
        verify(zsetOps, never()).remove(OUTBOX, "123");
        verify(hashOps, never()).put(eq(META), eq("123"), anyString());
        verify(downstreamProducer, never()).sendEnvelope(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void scan_online_resentsAndBacksOff() {
        Message m = sentMessage();
        long born = System.currentTimeMillis() - 1000;
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(expiredTuple("123")), Collections.emptySet());
        when(messageMapper.selectById(123L)).thenReturn(m);
        when(hashOps.get(META, "123")).thenReturn("0," + born);
        when(downstreamProducer.isOnline("B")).thenReturn(true);
        when(downstreamProducer.sendEnvelope(eq("B"), isNull(), eq(DownstreamEnvelope.TYPE_MSG),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        outboxService.scan();
        // 重推的 payload 来自 DB 行,serverMsgId/seq/receiver 齐全
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(downstreamProducer).sendEnvelope(eq("B"), isNull(), eq(DownstreamEnvelope.TYPE_MSG), payloadCaptor.capture());
        MessagePayload p = (MessagePayload) payloadCaptor.getValue();
        assertEquals("123", p.getServerMsgId());
        assertEquals(7L, p.getSeq());
        assertEquals("B", p.getReceiverId());
        // 退避:第 1 次重推后 30s 复查,attempts 记 1
        verify(zsetOps).add(eq(OUTBOX), eq("123"), anyDouble());
        verify(hashOps).put(META, "123", "1," + born);
    }

    @Test
    void scan_onlineButMqSendFails_keepsWithoutCountingAttempt() {
        Message m = sentMessage();
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(expiredTuple("123")), Collections.emptySet());
        when(messageMapper.selectById(123L)).thenReturn(m);
        when(hashOps.get(META, "123")).thenReturn("0," + System.currentTimeMillis());
        when(downstreamProducer.isOnline("B")).thenReturn(true);
        when(downstreamProducer.sendEnvelope(eq("B"), isNull(), eq(DownstreamEnvelope.TYPE_MSG),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);
        outboxService.scan();
        verify(zsetOps).add(eq(OUTBOX), eq("123"), anyDouble()); // 30s 复查
        verify(hashOps, never()).put(eq(META), eq("123"), anyString()); // 不计数
    }

    @Test
    void scan_attemptsExceeded_givesUpWithCleanup() {
        Message m = sentMessage();
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(expiredTuple("123")), Collections.emptySet());
        when(messageMapper.selectById(123L)).thenReturn(m);
        when(hashOps.get(META, "123")).thenReturn("5," + System.currentTimeMillis());
        when(downstreamProducer.isOnline("B")).thenReturn(true);
        outboxService.scan();
        verify(zsetOps).remove(OUTBOX, "123");
        verify(hashOps).delete(META, "123");
        verify(downstreamProducer, never()).sendEnvelope(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void scan_ageExceeded_givesUp() {
        Message m = sentMessage();
        long ancient = System.currentTimeMillis() - 8L * 24 * 3600 * 1000; // 8 天前
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(expiredTuple("123")), Collections.emptySet());
        when(messageMapper.selectById(123L)).thenReturn(m);
        when(hashOps.get(META, "123")).thenReturn("0," + ancient);
        when(downstreamProducer.isOnline("B")).thenReturn(true);
        outboxService.scan();
        verify(zsetOps).remove(OUTBOX, "123");
        verify(hashOps).delete(META, "123");
    }

    @Test
    void scan_roundPopsUntilFutureScore() {
        Message m = sentMessage();
        double futureScore = System.currentTimeMillis() + 60_000;
        when(zsetOps.popMin(OUTBOX, 1L))
                .thenReturn(Collections.singleton(expiredTuple("123")),
                        Collections.singleton(new DefaultTypedTuple<>("456", futureScore)));
        when(messageMapper.selectById(123L)).thenReturn(m);
        when(hashOps.get(META, "123")).thenReturn("0," + System.currentTimeMillis());
        when(downstreamProducer.isOnline("B")).thenReturn(true);
        when(downstreamProducer.sendEnvelope(eq("B"), isNull(), eq(DownstreamEnvelope.TYPE_MSG),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        outboxService.scan();
        // 123 被处理(重推),456 未到期被放回
        verify(zsetOps, times(2)).add(eq(OUTBOX), anyString(), anyDouble());
        verify(zsetOps).add(OUTBOX, "456", futureScore);
    }

    private DefaultTypedTuple<String> expiredTuple(String id) {
        return new DefaultTypedTuple<>(id, (double) (System.currentTimeMillis() - 1));
    }
}
