package com.quantumlink.im.chat.controller;

import com.quantumlink.im.chat.dto.ConversationListDto;
import com.quantumlink.im.chat.dto.MessagePageDto;
import com.quantumlink.im.chat.service.MessageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 增量拉取某会话 afterSeq 之后的消息,按 seq 升序。
     *
     * @param conversationId 会话 ID(A#B)
     * @param afterSeq       客户端已同步的最大 seq(位点)
     * @param limit          每页条数(默认 50,上限 200)
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public MessagePageDto pullMessages(
            @PathVariable("conversationId") String conversationId,
            @RequestParam("afterSeq") long afterSeq,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return messageQueryService.pullMessages(conversationId, afterSeq, limit);
    }

    /**
     * 会话列表:某用户参与的所有会话,按最后一条消息时间倒序。
     *
     * @param userId 用户 ID
     */
    @GetMapping("/conversations")
    public ConversationListDto listConversations(@RequestParam("userId") String userId) {
        return messageQueryService.listConversations(userId);
    }
}
