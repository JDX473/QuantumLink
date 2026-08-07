package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.config.AuthContext;
import com.quantumlink.im.chat.dto.GroupMessageItemDto;
import com.quantumlink.im.chat.dto.GroupMessagePageDto;
import com.quantumlink.im.chat.entity.Group;
import com.quantumlink.im.chat.service.GroupService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 群接口:群管理(创建/拉人/踢人/群列表)+ 群消息增量拉取。
 *
 * <p>群消息链路:读扩散(落库一份)+ 在线按节点聚合推送 + 离线按 seq 拉取(与单聊同构)。
 */
@Slf4j
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /**
     * 面对面建群:输入 4 位数字,加入该数字当前 5 分钟窗口的群(没有则创建)。
     * 并发输入同数字原子建群(Lua),时间窗口 Redis TTL 实现。
     */
    @PostMapping("/face2face")
    public Map<String, Object> face2face(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        String code = (String) req.get("code");
        Map<String, Object> resp = new HashMap<>();
        // 校验:4 位数字
        if (code == null || !code.matches("\\d{4}")) {
            resp.put("success", false);
            resp.put("message", "code must be 4 digits");
            return resp;
        }
        try {
            Map<String, Object> joined = groupService.joinByCode(code, AuthContext.currentUserId(request));
            resp.put("success", true);
            resp.putAll(joined);
            return resp;
        } catch (IllegalStateException e) {
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return resp;
        }
    }

    /** 创建群:name + members(初始成员,群主自动加入);群主从鉴权上下文取 */
    @PostMapping
    public Map<String, Object> createGroup(@RequestBody Map<String, Object> req, HttpServletRequest request) {        String name = (String) req.get("name");
        String ownerId = AuthContext.currentUserId(request); // 群主 = 当前登录用户,不信任参数
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) req.getOrDefault("members", List.of());
        Map<String, Object> resp = new HashMap<>();
        if (name == null || ownerId == null) {
            resp.put("success", false);
            resp.put("message", "name required");
            return resp;
        }
        Group group = groupService.createGroup(name, ownerId, members);
        resp.put("success", true);
        resp.put("groupId", group.getGroupId());
        resp.put("name", group.getName());
        return resp;
    }

    /** 拉人入群 */
    @PostMapping("/{groupId}/members")
    public Map<String, Object> addMember(@PathVariable("groupId") String groupId,
                                         @RequestBody Map<String, Object> req) {
        String userId = (String) req.get("userId");
        Map<String, Object> resp = new HashMap<>();
        if (userId == null) {
            resp.put("success", false);
            resp.put("message", "userId required");
            return resp;
        }
        groupService.addMember(groupId, userId, "MEMBER");
        resp.put("success", true);
        return resp;
    }

    /** 踢人出群 */
    @DeleteMapping("/{groupId}/members/{userId}")
    public Map<String, Object> removeMember(@PathVariable("groupId") String groupId,
                                            @PathVariable("userId") String userId) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", groupService.removeMember(groupId, userId));
        return resp;
    }

    /** 我的群列表(userId 从鉴权上下文取) */
    @GetMapping
    public Map<String, Object> listGroups(HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("groups", groupService.listGroupsByUser(AuthContext.currentUserId(request)));
        return resp;
    }

    /** 群成员列表 */
    @GetMapping("/{groupId}/members")
    public Map<String, Object> listMembers(@PathVariable("groupId") String groupId) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("memberIds", groupService.listMemberIds(groupId));
        return resp;
    }

    /** 群消息增量拉取(按 seq,与单聊同构;含发送者资料 + hasMore 分页)。越权防护:仅群成员可拉 */
    @GetMapping("/{groupId}/messages")
    public Map<String, Object> pullMessages(
            @PathVariable("groupId") String groupId,
            @RequestParam("afterSeq") long afterSeq,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        String userId = AuthContext.currentUserId(request);
        if (!groupService.isMember(groupId, userId)) {
            resp.put("success", false);
            resp.put("message", "forbidden: not a group member");
            return resp;
        }
        GroupMessagePageDto page = groupService.pullGroupMessages(groupId, afterSeq, limit);
        resp.put("success", true);
        resp.put("messages", page.getMessages());
        resp.put("maxSeq", page.getMaxSeq());
        resp.put("hasMore", page.isHasMore());
        return resp;
    }
}
