package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.config.AuthContext;
import com.quantumlink.im.chat.dto.ConversationListDto;
import com.quantumlink.im.chat.dto.MessagePageDto;
import com.quantumlink.im.chat.service.MessageQueryService;
import com.quantumlink.im.chat.service.ReadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息查询接口。
 *
 * <p>增量拉取:客户端携带会话位点(afterSeq),拉取该会话 afterSeq 之后的消息。
 * 用于:离线消息补拉、多端对齐、seq 空洞补拉。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MessageController {

    private final MessageQueryService messageQueryService;
    private final ReadService readService;

    /**
     * 增量拉取某会话 afterSeq 之后的消息,按 seq 升序。
     * 越权防护:仅允许会话参与者(会话 ID = A#B,当前用户必须是 A 或 B)拉取。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Object pullMessages(
            @PathVariable("conversationId") String conversationId,
            @RequestParam("afterSeq") long afterSeq,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        String userId = AuthContext.currentUserId(request);
        if (!AuthContext.isConversationParticipant(conversationId, userId)) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "forbidden: not a conversation participant");
            return resp;
        }
        MessagePageDto dto = messageQueryService.pullMessages(conversationId, afterSeq, limit);
        // 带上对端已读水位:客户端据此渲染自己消息的"已读/未读"(离线期间对端读的也在这补回)
        dto.setPeerReadSeq(readService.peerReadSeq(conversationId, userId));
        return dto;
    }

    /**
     * 会话列表:当前登录用户的会话,按最后一条消息时间倒序。
     * 越权防护:userId 从鉴权上下文取,不信任 URL 参数。
     */
    @GetMapping("/conversations")
    public ConversationListDto listConversations(HttpServletRequest request) {
        String userId = AuthContext.currentUserId(request);
        return messageQueryService.listConversations(userId);
    }
}
