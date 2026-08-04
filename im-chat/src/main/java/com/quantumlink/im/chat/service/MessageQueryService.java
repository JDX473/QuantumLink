package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.dto.ConversationListDto;
import com.quantumlink.im.chat.dto.MessagePageDto;
import com.quantumlink.im.chat.entity.Message;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.MessageMapper;
import com.quantumlink.im.chat.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息查询服务:离线消息 + 增量拉取。
 *
 * <p><b>为什么按 seq 拉取而不是按时间:</b>seq 是会话内单调位点,天然支持
 * 断点续拉(客户端上报已同步到的 max seq,服务端返回其后的)、多端对齐
 * (每端维护自己的位点)、乱序兜底(seq 排序)。时间戳跨时区/改时钟不可信。
 *
 * <p>离线模型:消息一律先落库;在线走推送,离线不推送,上线按 seq 增量拉取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    private final MessageMapper messageMapper;
    private final UserMapper userMapper;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /**
     * 按会话增量拉取:返回 {@code seq > afterSeq} 的消息,按 seq 升序。
     *
     * @param conversationId 会话 ID
     * @param afterSeq       客户端已同步的最大 seq(位点)
     * @param limit          每页条数
     */
    public MessagePageDto pullMessages(String conversationId, long afterSeq, Integer limit) {
        int pageSize = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        // 查 afterSeq 之后的消息,多取一条判断 hasMore
        List<Message> rows = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .gt(Message::getSeq, afterSeq)
                        .orderByAsc(Message::getSeq)
                        .last("LIMIT " + (pageSize + 1)));

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }

        List<MessagePageDto.MessageItem> items = new ArrayList<>(rows.size());
        for (Message m : rows) {
            MessagePageDto.MessageItem item = new MessagePageDto.MessageItem();
            item.setServerMsgId(m.getId());
            item.setSeq(m.getSeq());
            item.setConversationId(m.getConversationId());
            item.setSenderId(m.getSenderId());
            item.setMsgType(m.getMsgType());
            item.setContent(m.getContent());
            item.setServerTime(m.getServerTime());
            item.setStatus(m.getStatus());
            items.add(item);
        }

        MessagePageDto dto = new MessagePageDto();
        dto.setMessages(items);
        dto.setHasMore(hasMore);
        dto.setServerMaxSeq(serverMaxSeq(conversationId));
        return dto;
    }

    /** 本会话已落库的最大 seq(客户端据此判断"某个空洞是永久缺失还是暂未落库") */
    private long serverMaxSeq(String conversationId) {
        Message last = messageMapper.selectOne(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByDesc(Message::getSeq)
                        .last("LIMIT 1"));
        return last == null ? 0 : last.getSeq();
    }

    /**
     * 会话列表:该用户参与的所有会话,按最后一条消息时间倒序。
     * 每条含对方 userId/用户名、最后一条消息预览、最后时间。
     */
    public ConversationListDto listConversations(String userId) {
        // 查该用户参与的所有会话的消息(sender=我 或 receiver=我)
        List<Message> all = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSenderId, userId)
                        .or()
                        .eq(Message::getReceiverId, userId));

        // 按 conversationId 分组,每组取最后一条消息(按 seq 最大)
        Map<String, Message> lastByConv = new LinkedHashMap<>();
        for (Message m : all) {
            Message prev = lastByConv.get(m.getConversationId());
            if (prev == null || m.getSeq() > prev.getSeq()) {
                lastByConv.put(m.getConversationId(), m);
            }
        }

        // 解析对方 userId → username
        Set<String> peerIds = new HashSet<>();
        for (Message m : lastByConv.values()) {
            String peer = m.getSenderId().equals(userId) ? m.getReceiverId() : m.getSenderId();
            peerIds.add(peer);
        }
        Map<String, User> peerMap = new java.util.HashMap<>();
        if (!peerIds.isEmpty()) {
            List<User> peers = userMapper.selectList(
                    new LambdaQueryWrapper<User>().in(User::getUserId, peerIds));
            for (User u : peers) {
                peerMap.put(u.getUserId(), u);
            }
        }

        List<ConversationListDto.ConversationItem> items = new ArrayList<>();
        for (Message m : lastByConv.values()) {
            String peer = m.getSenderId().equals(userId) ? m.getReceiverId() : m.getSenderId();
            User peerUser = peerMap.get(peer);
            ConversationListDto.ConversationItem item = new ConversationListDto.ConversationItem();
            item.setConversationId(m.getConversationId());
            item.setPeerUserId(peer);
            item.setPeerUsername(peerUser != null ? peerUser.getUsername() : peer);
            item.setPeerAvatar(peerUser != null ? peerUser.getAvatarUrl() : null);
            item.setLastMessage(m.getContent());
            item.setLastTime(m.getServerTime());
            item.setLastSeq(m.getSeq());
            items.add(item);
        }

        // 按最后时间倒序(最近对话靠上)
        items.sort((a, b) -> Long.compare(b.getLastTime() == null ? 0 : b.getLastTime(),
                                          a.getLastTime() == null ? 0 : a.getLastTime()));

        ConversationListDto dto = new ConversationListDto();
        dto.setConversations(items);
        return dto;
    }
}
