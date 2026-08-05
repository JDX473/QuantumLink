package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.entity.Message;
import com.quantumlink.im.chat.mapper.ConversationMapper;
import com.quantumlink.im.chat.mapper.MessageMapper;
import com.quantumlink.im.chat.mapper.UserMapper;
import com.quantumlink.im.chat.mq.DownstreamProducer;
import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.protocol.MessagePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageServiceTest {

    private MessageMapper messageMapper;
    private ConversationMapper conversationMapper;
    private UserMapper userMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private DownstreamProducer downstreamProducer;
    private GroupService groupService;
    private UserCacheService userCacheService;
    private MessageService service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        userMapper = mock(UserMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        downstreamProducer = mock(DownstreamProducer.class);
        groupService = mock(GroupService.class);
        userCacheService = mock(UserCacheService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new MessageService(messageMapper, conversationMapper, userMapper,
                redisTemplate, downstreamProducer, groupService, userCacheService);
    }

    private MessagePayload payload(String sender, String receiver, String clientMsgId, String conv) {
        MessagePayload p = new MessagePayload();
        p.setSenderId(sender);
        p.setReceiverId(receiver);
        p.setClientMsgId(clientMsgId);
        p.setConversationId(conv);
        p.setContent("hi");
        return p;
    }

    @Test
    void handleUpstream_groupMessage_routesToGroupService() {
        MessagePayload p = payload("u1", "g_x", "c1", "g_x");
        boolean ok = service.handleUpstream(p);
        assertTrue(ok);
        verify(groupService).handleGroupMessage(p);
    }

    @Test
    void handleUpstream_duplicate_returnsFalse() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        Message existing = new Message();
        existing.setId(5L);
        existing.setSeq(3L);
        when(messageMapper.selectOne(any())).thenReturn(existing);

        boolean ok = service.handleUpstream(payload("u1", "u2", "dup1", "u1#u2"));
        assertFalse(ok);
        verify(downstreamProducer).sendEnvelope(eq("u1"), isNull(), eq(DownstreamEnvelope.TYPE_ACK), any());
    }

    @Test
    void handleUpstream_duplicate_noExisting_returnsFalse() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(messageMapper.selectOne(any())).thenReturn(null);
        assertFalse(service.handleUpstream(payload("u1", "u2", "dup2", "u1#u2")));
        verify(downstreamProducer, never()).sendEnvelope(anyString(), any(), anyString(), any());
    }

    @Test
    void handleUpstream_newMessage_assignsSeqAndSubmits() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(42L);

        boolean ok = service.handleUpstream(payload("u1", "u2", "new1", ""));
        assertTrue(ok);
        verify(valueOps).increment(eq("im:conv:seq:u1#u2")); // conversationId 自动构建
    }

    @Test
    void buildConversationId_stable() {
        assertEquals("a#b", MessageService.buildConversationId("a", "b"));
        assertEquals("a#b", MessageService.buildConversationId("b", "a"));
    }

    @Test
    void handleUpstream_asyncProcess_storesAndAcks() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(messageMapper.insert(any(Message.class))).thenReturn(1);
        UserCacheService.UserView view = new UserCacheService.UserView();
        view.setUsername("alice");
        when(userCacheService.getUser("u1")).thenReturn(view);

        boolean ok = service.handleUpstream(payload("u1", "u2", "new2", "u1#u2"));
        assertTrue(ok);
        // 等异步线程完成
        Thread.sleep(500);
        verify(messageMapper).insert(any(Message.class));
        // ACK 发给发送者 u1
        verify(downstreamProducer, atLeastOnce()).sendEnvelope(eq("u1"), isNull(), eq(DownstreamEnvelope.TYPE_ACK), any());
        // MSG 推给接收者 u2
        verify(downstreamProducer, atLeastOnce()).sendEnvelope(eq("u2"), isNull(), eq(DownstreamEnvelope.TYPE_MSG), any());
    }
}
