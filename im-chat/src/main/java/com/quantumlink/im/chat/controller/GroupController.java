package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.entity.Group;
import com.quantumlink.im.chat.entity.GroupMessage;
import com.quantumlink.im.chat.service.GroupService;
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

    /** 创建群:name + members(初始成员,群主自动加入) */
    @PostMapping
    public Map<String, Object> createGroup(@RequestBody Map<String, Object> req) {
        String name = (String) req.get("name");
        String ownerId = (String) req.get("ownerId");
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) req.getOrDefault("members", List.of());
        Map<String, Object> resp = new HashMap<>();
        if (name == null || ownerId == null) {
            resp.put("success", false);
            resp.put("message", "name and ownerId required");
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

    /** 我的群列表 */
    @GetMapping
    public Map<String, Object> listGroups(@RequestParam("userId") String userId) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("groups", groupService.listGroupsByUser(userId));
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

    /** 群消息增量拉取(按 seq,与单聊同构) */
    @GetMapping("/{groupId}/messages")
    public Map<String, Object> pullMessages(
            @PathVariable("groupId") String groupId,
            @RequestParam("afterSeq") long afterSeq,
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<GroupMessage> messages = groupService.pullGroupMessages(groupId, afterSeq, limit);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("messages", messages);
        resp.put("maxSeq", groupService.groupMaxSeq(groupId));
        return resp;
    }
}
