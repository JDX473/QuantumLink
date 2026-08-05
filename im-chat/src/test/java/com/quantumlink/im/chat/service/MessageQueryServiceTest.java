package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.dto.ConversationListDto;
import com.quantumlink.im.chat.dto.MessagePageDto;
import com.quantumlink.im.chat.entity.Message;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.MessageMapper;
import com.quantumlink.im.chat.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MessageQueryServiceTest {

    private MessageMapper messageMapper;
    private UserMapper userMapper;
    private MessageQueryService service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        userMapper = mock(UserMapper.class);
        service = new MessageQueryService(messageMapper, userMapper);
    }

    private Message msg(long id, long seq, String sender, String receiver, String conv, String content) {
        Message m = new Message();
        m.setId(id);
        m.setSeq(seq);
        m.setSenderId(sender);
        m.setReceiverId(receiver);
        m.setConversationId(conv);
        m.setContent(content);
        m.setStatus("SENT");
        return m;
    }

    @Test
    void pullMessages_returnsItemsWithSenderProfile() {
        Message m1 = msg(1L, 1L, "u_sender", "u_me", "u_me#u_sender", "hi");
        when(messageMapper.selectList(any())).thenReturn(List.of(m1));
        User sender = new User();
        sender.setUserId("u_sender");
        sender.setUsername("alice");
        sender.setAvatarUrl("http://a.png");
        when(userMapper.selectList(any())).thenReturn(List.of(sender));

        MessagePageDto dto = service.pullMessages("u_me#u_sender", 0, 10);
        assertEquals(1, dto.getMessages().size());
        MessagePageDto.MessageItem item = dto.getMessages().get(0);
        assertEquals("u_sender", item.getSenderId());
        assertEquals("alice", item.getSenderName());
        assertEquals("http://a.png", item.getSenderAvatar());
        assertEquals("hi", item.getContent());
        assertFalse(dto.isHasMore());
    }

    @Test
    void pullMessages_hasMore_whenExceedsLimit() {
        List<Message> rows = new ArrayList<>();
        for (int i = 0; i < 11; i++) rows.add(msg(i, i, "u1", "u2", "u1#u2", "m" + i));
        when(messageMapper.selectList(any())).thenReturn(rows);
        when(userMapper.selectList(any())).thenReturn(List.of());

        MessagePageDto dto = service.pullMessages("u1#u2", 0, 10);
        assertTrue(dto.isHasMore());
        assertEquals(10, dto.getMessages().size());
    }

    @Test
    void pullMessages_empty() {
        when(messageMapper.selectList(any())).thenReturn(List.of());
        MessagePageDto dto = service.pullMessages("u1#u2", 0, 10);
        assertTrue(dto.getMessages().isEmpty());
        assertFalse(dto.isHasMore());
        assertEquals(0L, dto.getServerMaxSeq());
    }

    @Test
    void listConversations_groupsByConversationAndResolvesPeer() {
        Message m1 = msg(1L, 1L, "u_me", "u_peer", "u_me#u_peer", "first");
        Message m2 = msg(2L, 2L, "u_peer", "u_me", "u_me#u_peer", "second");
        when(messageMapper.selectList(any())).thenReturn(List.of(m1, m2));
        User peer = new User();
        peer.setUserId("u_peer");
        peer.setUsername("jdx");
        peer.setAvatarUrl("http://p.png");
        when(userMapper.selectList(any())).thenReturn(List.of(peer));

        ConversationListDto dto = service.listConversations("u_me");
        assertEquals(1, dto.getConversations().size());
        ConversationListDto.ConversationItem item = dto.getConversations().get(0);
        assertEquals("u_peer", item.getPeerUserId());
        assertEquals("jdx", item.getPeerUsername());
        assertEquals("http://p.png", item.getPeerAvatar());
        assertEquals("second", item.getLastMessage());
    }

    @Test
    void listConversations_noMessages_empty() {
        when(messageMapper.selectList(any())).thenReturn(List.of());
        ConversationListDto dto = service.listConversations("u_me");
        assertTrue(dto.getConversations().isEmpty());
    }
}
