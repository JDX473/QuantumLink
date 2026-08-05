package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.entity.Group;
import com.quantumlink.im.chat.entity.GroupMember;
import com.quantumlink.im.chat.mapper.GroupMapper;
import com.quantumlink.im.chat.mapper.GroupMemberMapper;
import com.quantumlink.im.chat.mapper.GroupMessageMapper;
import com.quantumlink.im.chat.mapper.UserMapper;
import com.quantumlink.im.chat.mq.DownstreamProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroupServiceTest {

    private GroupMapper groupMapper;
    private GroupMemberMapper memberMapper;
    private GroupMessageMapper messageMapper;
    private UserMapper userMapper;
    private StringRedisTemplate redisTemplate;
    private DownstreamProducer downstreamProducer;
    private UserCacheService userCacheService;
    private GroupService groupService;

    @BeforeEach
    void setUp() {
        groupMapper = mock(GroupMapper.class);
        memberMapper = mock(GroupMemberMapper.class);
        messageMapper = mock(GroupMessageMapper.class);
        userMapper = mock(UserMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        downstreamProducer = mock(DownstreamProducer.class);
        userCacheService = mock(UserCacheService.class);
        groupService = new GroupService(groupMapper, memberMapper, messageMapper,
                userMapper, redisTemplate, downstreamProducer, userCacheService);
        when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
    }

    @Test
    void createGroup_ownerAndMembers() {
        when(groupMapper.insert(any(Group.class))).thenReturn(1);
        when(memberMapper.selectCount(any())).thenReturn(0L);
        Group g = groupService.createGroup("测试群", "u_owner", List.of("u1", "u2"));
        assertNotNull(g.getGroupId());
        assertTrue(g.getGroupId().startsWith("g_"));
        assertEquals("测试群", g.getName());
        // owner + u1 + u2 都加入(owner 是 OWNER)
        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberMapper, times(3)).insert(captor.capture());
        boolean ownerRole = captor.getAllValues().stream()
                .anyMatch(m -> m.getUserId().equals("u_owner") && "OWNER".equals(m.getRole()));
        assertTrue(ownerRole);
    }

    @Test
    void createGroup_nullMembers_onlyOwner() {
        when(groupMapper.insert(any(Group.class))).thenReturn(1);
        when(memberMapper.selectCount(any())).thenReturn(0L);
        Group g = groupService.createGroup("g", "u_owner", null);
        verify(memberMapper, times(1)).insert(any(GroupMember.class));
        assertEquals("u_owner", g.getOwnerId());    }

    @Test
    void addMember_alreadyExists_noop() {
        when(memberMapper.selectCount(any())).thenReturn(1L);
        groupService.addMember("g1", "u1", "MEMBER");
        verify(memberMapper, never()).insert(any(GroupMember.class));
    }

    @Test
    void addMember_newMember_inserts() {
        when(memberMapper.selectCount(any())).thenReturn(0L);
        groupService.addMember("g1", "u1", "MEMBER");
        ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberMapper).insert(captor.capture());
        assertEquals("g1", captor.getValue().getGroupId());
        assertEquals("u1", captor.getValue().getUserId());
        assertEquals("MEMBER", captor.getValue().getRole());
    }

    @Test
    void joinByCode_createNewGroup() {
        // Lua 返回 CREATE
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(List.of("CREATE", "g_new1"));
        when(groupMapper.insert(any(Group.class))).thenReturn(1);
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(groupMapper.selectOne(any())).thenReturn(grp("g_new1", "面对面建群 1234"));

        Map<String, Object> r = groupService.joinByCode("1234", "u1");
        assertEquals("g_new1", r.get("groupId"));
        assertEquals(true, r.get("isNewGroup"));
        assertEquals("面对面建群 1234", r.get("name"));
        verify(groupMapper).insert(any(Group.class)); // 新建了群
    }

    @Test
    void joinByCode_existingGroup_noCreate() {
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(List.of("EXIST", "g_old"));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(groupMapper.selectOne(any())).thenReturn(grp("g_old", "面对面建群 1234"));

        Map<String, Object> r = groupService.joinByCode("1234", "u2");
        assertEquals("g_old", r.get("groupId"));
        assertEquals(false, r.get("isNewGroup"));
        verify(groupMapper, never()).insert(any(Group.class)); // 不新建
    }

    @Test
    void joinByCode_fullGroup_throws() {
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(List.of("EXIST", "g_full"));
        // 200 个成员(不含当前用户)→ 满
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 200; i++) members.add("u_" + i);
        when(memberMapper.selectCount(any())).thenReturn(1L); // 当前用户已在?不,先走 listMemberIds
        // 需要 stub listMemberIds → memberMapper.selectList 返回 200 个
        when(memberMapper.selectList(any())).thenReturn(members.stream().map(uid -> {
            GroupMember m = new GroupMember();
            m.setUserId(uid);
            return m;
        }).collect(java.util.stream.Collectors.toList()));
        // 当前用户不在其中
        assertThrows(IllegalStateException.class, () -> groupService.joinByCode("1234", "u_not_in"));
    }

    @Test
    void removeMember_deletes() {
        when(memberMapper.delete(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(1);
        assertTrue(groupService.removeMember("g1", "u1"));
        when(memberMapper.delete(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(0);
        assertFalse(groupService.removeMember("g1", "u1"));
    }

    @Test
    void isMember_trueAndFalse() {
        when(memberMapper.selectCount(any())).thenReturn(1L);
        assertTrue(groupService.isMember("g1", "u1"));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        assertFalse(groupService.isMember("g1", "u2"));
    }

    private Group grp(String gid, String name) {
        Group g = new Group();
        g.setGroupId(gid);
        g.setName(name);
        return g;
    }

    // ==================== 群消息 ====================

    private com.quantumlink.im.common.protocol.MessagePayload groupMsg(String sender, String gid, String cmid) {
        com.quantumlink.im.common.protocol.MessagePayload p = new com.quantumlink.im.common.protocol.MessagePayload();
        p.setSenderId(sender);
        p.setConversationId(gid);
        p.setClientMsgId(cmid);
        p.setContent("hi");
        p.setMsgType("TEXT");
        return p;
    }

    @Test
    void handleGroupMessage_duplicate_skips() {
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(false);
        groupService.handleGroupMessage(groupMsg("u1", "g_x", "dup"));
        verify(messageMapper, never()).insert(any(com.quantumlink.im.chat.entity.GroupMessage.class));
    }

    @Test
    void handleGroupMessage_newMessage_storesAndPushesToOthers() throws Exception {
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(redisTemplate.opsForValue().increment(anyString())).thenReturn(1L);
        when(messageMapper.insert(any(com.quantumlink.im.chat.entity.GroupMessage.class))).thenReturn(1);
        // 群成员:发送者 u1 + 其他 u2/u3
        when(memberMapper.selectList(any())).thenReturn(List.of(member("g_x", "u1"), member("g_x", "u2"), member("g_x", "u3")));
        UserCacheService.UserView view = new UserCacheService.UserView();
        view.setUsername("u1");
        when(userCacheService.getUser("u1")).thenReturn(view);

        groupService.handleGroupMessage(groupMsg("u1", "g_x", "new1"));
        Thread.sleep(300); // 落库在同步路径,无需等;但验证推送给 u2/u3(不含发送者 u1)
        verify(messageMapper).insert(any(com.quantumlink.im.chat.entity.GroupMessage.class));
        // 群播推给 u2/u3(不含 u1)
        verify(downstreamProducer).sendGroupEnvelope(argThat(list -> list.size() == 2 && !list.contains("u1")),
                eq("MSG"), any());
        // ACK 回给发送者 u1
        verify(downstreamProducer).sendEnvelope(eq("u1"), isNull(), eq("ACK"), any());
    }

    private GroupMember member(String gid, String uid) {
        GroupMember m = new GroupMember();
        m.setGroupId(gid);
        m.setUserId(uid);
        return m;
    }
}
