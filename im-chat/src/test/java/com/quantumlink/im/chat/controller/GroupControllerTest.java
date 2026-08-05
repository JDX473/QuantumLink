package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.config.AuthContext;
import com.quantumlink.im.chat.config.AuthInterceptor;
import com.quantumlink.im.chat.entity.Group;
import com.quantumlink.im.chat.service.GroupService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GroupControllerTest {

    private GroupService groupService;
    private HttpServletRequest request;
    private GroupController controller;

    @BeforeEach
    void setUp() {
        groupService = mock(GroupService.class);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn("u_me");
        controller = new GroupController(groupService);
    }

    @Test
    void face2face_validCode() {
        Map<String, Object> joined = new HashMap<>();
        joined.put("groupId", "g_1");
        joined.put("name", "面对面建群 1234");
        joined.put("isNewGroup", true);
        when(groupService.joinByCode("1234", "u_me")).thenReturn(joined);

        Map<String, Object> req = new HashMap<>();
        req.put("code", "1234");
        Map<String, Object> resp = controller.face2face(req, request);
        assertEquals(true, resp.get("success"));
        assertEquals("g_1", resp.get("groupId"));
    }

    @Test
    void face2face_invalidCode_rejected() {
        Map<String, Object> req = new HashMap<>();
        req.put("code", "12ab");
        Map<String, Object> resp = controller.face2face(req, request);
        assertEquals(false, resp.get("success"));
        verify(groupService, never()).joinByCode(anyString(), anyString());
    }

    @Test
    void face2face_fullGroup_returnsMessage() {
        when(groupService.joinByCode("1234", "u_me"))
                .thenThrow(new IllegalStateException("group is full: g_1"));
        Map<String, Object> req = new HashMap<>();
        req.put("code", "1234");
        Map<String, Object> resp = controller.face2face(req, request);
        assertEquals(false, resp.get("success"));
        assertEquals("group is full: g_1", resp.get("message"));
    }

    @Test
    void createGroup_missingName_rejected() {
        Map<String, Object> req = new HashMap<>();
        req.put("members", List.of());
        Map<String, Object> resp = controller.createGroup(req, request);
        assertEquals(false, resp.get("success"));
    }

    @Test
    void createGroup_success() {
        Group g = new Group();
        g.setGroupId("g_1");
        g.setName("测试群");
        when(groupService.createGroup("测试群", "u_me", List.of("u1"))).thenReturn(g);

        Map<String, Object> req = new HashMap<>();
        req.put("name", "测试群");
        req.put("members", List.of("u1"));
        Map<String, Object> resp = controller.createGroup(req, request);
        assertEquals(true, resp.get("success"));
        assertEquals("g_1", resp.get("groupId"));
    }

    @Test
    void removeMember_delegates() {
        when(groupService.removeMember("g_1", "u1")).thenReturn(true);
        assertEquals(true, controller.removeMember("g_1", "u1").get("success"));
        when(groupService.removeMember("g_1", "u1")).thenReturn(false);
        assertEquals(false, controller.removeMember("g_1", "u1").get("success"));
    }

    @Test
    void listGroups_usesContextUser() {
        when(groupService.listGroupsByUser("u_me")).thenReturn(List.of());
        controller.listGroups(request);
        verify(groupService).listGroupsByUser("u_me");
    }

    @Test
    void pullMessages_notMember_forbidden() {
        when(groupService.isMember("g_1", "u_me")).thenReturn(false);
        Map<String, Object> resp = controller.pullMessages("g_1", 0, 10, request);
        assertEquals(false, resp.get("success"));
        verify(groupService, never()).pullGroupMessages(anyString(), anyLong(), any());
    }

    @Test
    void pullMessages_member_returnsMessages() {
        when(groupService.isMember("g_1", "u_me")).thenReturn(true);
        when(groupService.pullGroupMessages("g_1", 0, 10)).thenReturn(List.of());
        when(groupService.groupMaxSeq("g_1")).thenReturn(5L);
        Map<String, Object> resp = controller.pullMessages("g_1", 0, 10, request);
        assertEquals(true, resp.get("success"));
        assertEquals(5L, resp.get("maxSeq"));
    }
}
