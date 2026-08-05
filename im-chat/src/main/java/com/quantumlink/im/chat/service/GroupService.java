package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.dto.GroupMessageItemDto;
import com.quantumlink.im.chat.entity.Group;
import com.quantumlink.im.chat.entity.GroupMember;
import com.quantumlink.im.chat.entity.GroupMessage;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.GroupMapper;
import com.quantumlink.im.chat.mapper.GroupMemberMapper;
import com.quantumlink.im.chat.mapper.GroupMessageMapper;
import com.quantumlink.im.chat.mapper.UserMapper;
import com.quantumlink.im.chat.mq.DownstreamProducer;
import com.quantumlink.im.common.protocol.AckPayload;
import com.quantumlink.im.common.protocol.AckType;
import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.protocol.MessagePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 群服务:群管理(创建/拉人/踢人/群列表)+ 群消息(读扩散:落一份,在线推送,离线拉取)。
 *
 * <p><b>扩散模型 = 读扩散 + 在线推送</b>:
 * <ul>
 *   <li>群消息落库 <b>一份</b>(im_group_message),群成员各自维护 seq 位点拉取(与单聊同构);</li>
 *   <li>在线成员由 chat 按节点聚合 → 每节点一条 targets 信封推送(MQ 扇出 = 节点数而非成员数);</li>
 *   <li>离线成员不推送,上线按 seq 增量拉取。</li>
 * </ul>
 *
 * <p><b>为什么小群用读扩散</b>:成员少时写一份 + 各自拉取,写放大为 0;在线推送的扩散
 * 靠"按节点聚合"压到节点数级。大群(>500 人)才需要写扩散/收件箱,此处不做。
 *
 * <p><b>群消息不回 DELIVER</b>(微信群无"对方已送达"),只回 ACK-STORE 给发送者。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final GroupMessageMapper messageMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final DownstreamProducer downstreamProducer;
    private final UserCacheService userCacheService;

    /** 群维度 seq 发号 key(Redis INCR,与单聊会话 seq 同构) */
    private static final String GROUP_SEQ_PREFIX = "im:group_seq:";

    /** 群消息幂等去重 key */
    private static final String GROUP_DEDUP_PREFIX = "im:group_msg:dedup:";
    private static final long DEDUP_TTL_SECONDS = 7 * 24 * 3600;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    // ==================== 群管理 ====================

    /** 创建群:群主 + 初始成员 */
    public Group createGroup(String name, String ownerId, List<String> memberIds) {
        Group group = new Group();
        group.setGroupId("g_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        group.setName(name);
        group.setOwnerId(ownerId);
        groupMapper.insert(group);

        // 群主 + 初始成员
        Set<String> all = new HashSet<>();
        all.add(ownerId);
        if (memberIds != null) all.addAll(memberIds);
        for (String uid : all) {
            addMember(group.getGroupId(), uid, uid.equals(ownerId) ? "OWNER" : "MEMBER");
        }
        log.info("group created: groupId={} name={} owner={} members={}", group.getGroupId(), name, ownerId, all.size());
        return group;
    }

    /** 拉人入群(幂等:重复加入忽略) */
    public void addMember(String groupId, String userId, String role) {
        Long exists = memberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId));
        if (exists != null && exists > 0) return;
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(role == null ? "MEMBER" : role);
        memberMapper.insert(m);
    }

    /** 踢人出群 */
    public boolean removeMember(String groupId, String userId) {
        int removed = memberMapper.delete(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId));
        return removed > 0;
    }

    /** 是否群成员 */
    public boolean isMember(String groupId, String userId) {
        Long count = memberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId));
        return count != null && count > 0;
    }

    /** 群成员 userId 列表(可变 List,调用方可能做 remove 等修改) */
    public List<String> listMemberIds(String groupId) {
        List<String> ids = new ArrayList<>();
        for (GroupMember m : memberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId))) {
            ids.add(m.getUserId());
        }
        return ids;
    }

    /** 某用户加入的群列表(含群名) */
    public List<Group> listGroupsByUser(String userId) {
        List<GroupMember> mine = memberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId));
        if (mine.isEmpty()) return List.of();
        List<String> gids = mine.stream().map(GroupMember::getGroupId).toList();
        return groupMapper.selectList(
                new LambdaQueryWrapper<Group>().in(Group::getGroupId, gids));
    }

    public Group getGroup(String groupId) {
        return groupMapper.selectOne(
                new LambdaQueryWrapper<Group>().eq(Group::getGroupId, groupId));
    }

    // ==================== 群消息 ====================

    /**
     * 群消息上行处理(读扩散):
     * 幂等去重 → 群维度取 seq → 落库一份 → 在线成员按节点聚合推送 → 回 ACK-STORE。
     */
    public void handleGroupMessage(MessagePayload payload) {
        String groupId = payload.getConversationId(); // 群消息 conversationId = 群 id
        String dedupKey = GROUP_DEDUP_PREFIX + payload.getSenderId() + ":" + payload.getClientMsgId();
        Boolean first = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(first)) {
            log.info("duplicate group message, skip: sender={} clientMsgId={}", payload.getSenderId(), payload.getClientMsgId());
            return;
        }

        // 群维度发号(Redis INCR,与单聊会话 seq 同构)
        Long seq = redisTemplate.opsForValue().increment(GROUP_SEQ_PREFIX + groupId);

        // 落库一份(读扩散:不按成员复制)
        GroupMessage gm = new GroupMessage();
        gm.setClientMsgId(payload.getClientMsgId());
        gm.setGroupId(groupId);
        gm.setSenderId(payload.getSenderId());
        gm.setMsgType(payload.getMsgType() == null ? "TEXT" : payload.getMsgType());
        gm.setContent(payload.getContent());
        gm.setSeq(seq);
        gm.setStatus("SENT");
        gm.setServerTime(System.currentTimeMillis());
        try {
            messageMapper.insert(gm);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("duplicate group message by DB key: sender={} clientMsgId={}", payload.getSenderId(), payload.getClientMsgId());
            return;
        }
        log.info("group message stored: group={} seq={} sender={}", groupId, seq, payload.getSenderId());

        // 下行扩散:发给除发送者外的所有成员(在线成员按节点聚合推送)
        List<String> memberIds = listMemberIds(groupId);
        memberIds.remove(payload.getSenderId()); // 发送者自己不回推(本地已展示)
        if (!memberIds.isEmpty()) {
            payload.setServerMsgId(gm.getId());
            payload.setSeq(seq);
            payload.setServerTime(gm.getServerTime());
            fillSenderProfile(payload);
            downstreamProducer.sendGroupEnvelope(memberIds, DownstreamEnvelope.TYPE_MSG, payload);
        }

        // 回 ACK-STORE 给发送者(群消息不回 DELIVER)
        AckPayload ack = new AckPayload();
        ack.setAckType(AckType.STORE);
        ack.setClientMsgId(payload.getClientMsgId());
        ack.setServerMsgId(gm.getId());
        ack.setSeq(seq);
        ack.setConversationId(groupId);
        downstreamProducer.sendEnvelope(payload.getSenderId(), null, DownstreamEnvelope.TYPE_ACK, ack);
        log.info("group ACK-STORE sent: sender={} seq={}", payload.getSenderId(), seq);
    }

    /** 群消息增量拉取(按 seq,与单聊同构):返回 seq > afterSeq 的消息(含发送者资料) */
    public List<GroupMessageItemDto> pullGroupMessages(String groupId, long afterSeq, Integer limit) {
        int pageSize = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<GroupMessage> rows = messageMapper.selectList(
                new LambdaQueryWrapper<GroupMessage>()
                        .eq(GroupMessage::getGroupId, groupId)
                        .gt(GroupMessage::getSeq, afterSeq)
                        .orderByAsc(GroupMessage::getSeq)
                        .last("LIMIT " + pageSize));

        // 批量取发送者资料(与单聊 pullMessages 一致,一次查完)
        Set<String> senderIds = new HashSet<>();
        for (GroupMessage m : rows) senderIds.add(m.getSenderId());
        Map<String, User> senderMap = new java.util.HashMap<>();
        if (!senderIds.isEmpty()) {
            List<User> senders = userMapper.selectList(
                    new LambdaQueryWrapper<User>().in(User::getUserId, senderIds));
            for (User u : senders) senderMap.put(u.getUserId(), u);
        }

        List<GroupMessageItemDto> items = new ArrayList<>(rows.size());
        for (GroupMessage m : rows) {
            GroupMessageItemDto item = new GroupMessageItemDto();
            item.setServerMsgId(m.getId());
            item.setSeq(m.getSeq());
            item.setGroupId(m.getGroupId());
            item.setSenderId(m.getSenderId());
            User sender = senderMap.get(m.getSenderId());
            if (sender != null) {
                item.setSenderName(sender.getUsername());
                item.setSenderAvatar(sender.getAvatarUrl());
            }
            item.setMsgType(m.getMsgType());
            item.setContent(m.getContent());
            item.setServerTime(m.getServerTime());
            item.setStatus(m.getStatus());
            items.add(item);
        }
        return items;
    }

    /** 群当前最大 seq(水位线) */
    public long groupMaxSeq(String groupId) {
        GroupMessage last = messageMapper.selectOne(
                new LambdaQueryWrapper<GroupMessage>()
                        .eq(GroupMessage::getGroupId, groupId)
                        .orderByDesc(GroupMessage::getSeq)
                        .last("LIMIT 1"));
        return last == null ? 0 : last.getSeq();
    }

    /** 填充发送者用户名 + 头像(与单聊 fillSenderProfile 一致);走用户资料缓存 */
    private void fillSenderProfile(MessagePayload payload) {
        try {
            UserCacheService.UserView sender = userCacheService.getUser(payload.getSenderId());
            if (sender != null) {
                payload.setSenderName(sender.getUsername());
                payload.setSenderAvatar(sender.getAvatarUrl());
            }
        } catch (Exception e) {
            log.warn("fill group sender profile failed: sender={}", payload.getSenderId(), e);
        }
    }
}
