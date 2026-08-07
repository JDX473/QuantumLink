package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.config.AuthContext;
import com.quantumlink.im.chat.entity.GroupMember;
import com.quantumlink.im.chat.entity.GroupMessage;
import com.quantumlink.im.chat.entity.ReadPos;
import com.quantumlink.im.chat.mapper.GroupMemberMapper;
import com.quantumlink.im.chat.mapper.GroupMessageMapper;
import com.quantumlink.im.chat.mapper.ReadPosMapper;
import com.quantumlink.im.chat.mq.DownstreamProducer;
import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.protocol.ReadReportPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 已读服务:处理接收方的已读上报(水位推进)+ 对端已读水位查询。
 *
 * <p><b>为什么用"读水位"而非"每消息已读状态"</b>:seq 会话内单调递增,
 * 一条水位"读到 seq X" = "≤X 全部已读",O(1) 表达。已读是<b>派生状态</b>——
 * 发送方用"对端水位"推导自己消息的已读,不写共享的 im_message 行
 * (A#B 共享一行,逐条标已读分不清方向;水位存独立表,按读者一行)。
 *
 * <p><b>可靠性与实时性分离</b>:
 * <ul>
 *   <li>Redis 水位(im:read:{conv}:{reader})——实时推进与查询,原子单调(Lua);</li>
 *   <li>MySQL im_read_pos——持久化锚点(重启不丢),GREATEST 防多实例并发回退;</li>
 *   <li>READ 事件推给对端(在线秒级感知);对端离线不丢——下拉接口带 peerReadSeq 兜底。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadService {

    private final StringRedisTemplate redisTemplate;
    private final ReadPosMapper readPosMapper;
    private final DownstreamProducer downstreamProducer;
    /** 群成员校验(群已读越权防护) */
    private final GroupMemberMapper groupMemberMapper;
    /** 群消息发送者查询(群已读实时通知受影响发送者) */
    private final GroupMessageMapper groupMessageMapper;

    /** Redis 水位 key 前缀:im:read:{conversationId}:{readerId} → seq */
    private static final String READ_PREFIX = "im:read:";
    /** 水位 TTL(读水位是展示增强,非可靠性锚点;MySQL 是持久化源,过期后读库兜底) */
    private static final long READ_TTL_SECONDS = 7 * 24 * 3600;

    // ==================== 群已读 ====================

    /** 群成员已读水位 key 前缀:im:group_read:{groupId}:{memberId} → maxReadSeq(用户级,多端共享) */
    private static final String GROUP_READ_PREFIX = "im:group_read:";
    /** 群消息已读计数 key 前缀:im:group_msg_read:{groupId}:{seq} → count(预聚合) */
    private static final String GROUP_MSG_READ_PREFIX = "im:group_msg_read:";
    /** 成员水位 TTL(30 天;必须 > 计数 TTL,防"水位过期后对已读老消息重复计数"窗口扩大) */
    private static final long GROUP_WATERMARK_TTL_SECONDS = 30 * 24 * 3600;
    /** 消息计数 TTL(7 天,老消息不需要已读数) */
    private static final long GROUP_COUNT_TTL_SECONDS = 7 * 24 * 3600;
    /** 群已读实时推送的最大区间:超过则跳过实时推送(批量开群读场景,发送者重开即可看到最新计数) */
    private static final int MAX_LIVE_PUSH_RANGE = 20;

    /**
     * 群已读 Lua:成员水位只进不退 + 区间每条消息计数 INCR,整体原子。
     * Redis 单线程执行,多设备/多实例/乱序上报不重复计数。
     * 返回:推进时返回旧水位(cur,>=0);未推进(乱序/重复)返回 -1。
     */
    private static final String GROUP_READ_LUA =
            "local cur = tonumber(redis.call('GET', KEYS[1]) or '0') " +
            "if cur >= tonumber(ARGV[1]) then return -1 end " +
            "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3]) " +
            "for s = cur + 1, tonumber(ARGV[1]) do " +
            "  redis.call('INCR', ARGV[2] .. s) " +
            "  redis.call('EXPIRE', ARGV[2] .. s, ARGV[3]) " +
            "end " +
            "return cur";

    /**
     * Lua 原子单调推进:当前水位 ≥ 上报值(乱序/回退)→ 忽略返回 0;
     * 否则 SET(带 TTL 续期)返回 1。Redis 单线程执行,多 chat 实例并发上报无竞态。
     */
    private static final String READ_LUA =
            "local cur = tonumber(redis.call('GET', KEYS[1]) or '0') " +
            "if cur >= tonumber(ARGV[1]) then return 0 end " +
            "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
            "return 1";

    /**
     * 处理已读上报:校验参与者 → 原子推进水位 → 持久化 → 推 READ 事件给对端。
     *
     * <p>幂等/回退安全:水位只进不退,重复上报、乱序上报、多端上报取最大值。
     */
    /**
     * 已读上报分流:conversationId 以 g_ 开头 = 群已读(成员水位 + 预聚合计数,不推);
     * 否则单聊已读(对端水位 + 推 READ 事件)。协议/通道复用同一 READ_ACK + read_report。
     */
    public void handleReadReport(ReadReportPayload report) {
        String convId = report.getConversationId();
        if (convId != null && convId.startsWith("g_")) {
            handleGroupReadReport(report);
        } else {
            handleSingleReadReport(report);
        }
    }

    /** 单聊已读:对端水位单调推进 + 持久化 + 推 READ 事件给对端 */
    private void handleSingleReadReport(ReadReportPayload report) {
        String convId = report.getConversationId();
        String readerId = report.getReaderId();
        Long untilSeq = report.getUntilSeq();
        if (convId == null || readerId == null || untilSeq == null || untilSeq <= 0) {
            log.warn("bad read report, skip");
            return;
        }
        // 越权防护:reader 必须是会话参与者(A#B 之一)
        if (!AuthContext.isConversationParticipant(convId, readerId)) {
            log.warn("read report from non-participant, skip: reader={} conv={}", readerId, convId);
            return;
        }

        // ① 原子单调推进(返回 1=推进,0=乱序/回退忽略)
        Long advanced = redisTemplate.execute(
                new DefaultRedisScript<>(READ_LUA, Long.class),
                List.of(READ_PREFIX + convId + ":" + readerId),
                String.valueOf(untilSeq), String.valueOf(READ_TTL_SECONDS));
        if (advanced == null || advanced != 1L) {
            log.debug("read watermark not advanced, skip: reader={} conv={} untilSeq={}", readerId, convId, untilSeq);
            return;
        }

        // ② 持久化(可靠性锚点;GREATEST 双保险防回退)
        try {
            readPosMapper.upsert(readerId, convId, untilSeq);
        } catch (Exception e) {
            log.error("upsert read pos failed: reader={} conv={}", readerId, convId, e);
        }

        // ③ 推 READ 事件给对端(在线秒级感知;离线由下拉接口 peerReadSeq 兜底,不丢)
        String peer = peer(convId, readerId);
        if (peer == null) {
            return;
        }
        ReadReportPayload evt = new ReadReportPayload();
        evt.setConversationId(convId);
        evt.setReaderId(readerId);
        evt.setUntilSeq(untilSeq);
        downstreamProducer.sendEnvelope(peer, null, DownstreamEnvelope.TYPE_READ, evt);
        log.info("read watermark advanced: reader={} conv={} untilSeq={}", readerId, convId, untilSeq);
    }

    /**
     * 群已读:成员水位只进不退 + 区间每条消息计数 INCR(预聚合,查询 O(1))。
     * 不推 READ 事件(广播 = 写放大),已读数按需查(拉取接口带 readCount)。
     */
    private void handleGroupReadReport(ReadReportPayload report) {
        String groupId = report.getConversationId();
        String readerId = report.getReaderId();
        Long untilSeq = report.getUntilSeq();
        if (groupId == null || readerId == null || untilSeq == null || untilSeq <= 0) {
            log.warn("bad group read report, skip");
            return;
        }
        // 越权防护:reader 必须是群成员
        if (!isGroupMember(groupId, readerId)) {
            log.warn("group read report from non-member, skip: member={} group={}", readerId, groupId);
            return;
        }
        // ① 水位只进不退 + 区间 INCR(整体原子;返回旧水位 >=0 = 推进,-1 = 忽略)
        Long oldCur = redisTemplate.execute(
                new DefaultRedisScript<>(GROUP_READ_LUA, Long.class),
                List.of(GROUP_READ_PREFIX + groupId + ":" + readerId),
                String.valueOf(untilSeq),
                GROUP_MSG_READ_PREFIX + groupId + ":",
                String.valueOf(GROUP_COUNT_TTL_SECONDS));
        if (oldCur == null || oldCur < 0) {
            log.debug("group read watermark not advanced, skip: member={} group={} untilSeq={}", readerId, groupId, untilSeq);
            return;
        }
        log.info("group read watermark advanced: member={} group={} untilSeq={}", readerId, groupId, untilSeq);

        // ② 实时通知受影响消息的发送者(只推给发送者,非群广播;批量开群读不推,避免事件风暴)
        notifyAffectedSenders(groupId, readerId, oldCur, untilSeq);
    }

    /**
     * 群已读推进后,实时通知受影响消息的发送者"你那条消息 n人已读"。
     * 只推给发送者(非群广播——广播是写放大);批量开群读(区间 > MAX_LIVE_PUSH_RANGE)跳过,
     * 发送者重开会话时从拉取接口拿到最新计数。
     */
    private void notifyAffectedSenders(String groupId, String readerId, long fromSeq, long toSeq) {
        int range = (int) (toSeq - fromSeq);
        if (range <= 0 || range > MAX_LIVE_PUSH_RANGE) {
            return;
        }
        List<GroupMessage> affected = groupMessageMapper.selectList(
                new LambdaQueryWrapper<GroupMessage>()
                        .eq(GroupMessage::getGroupId, groupId)
                        .gt(GroupMessage::getSeq, fromSeq)
                        .le(GroupMessage::getSeq, toSeq));
        if (affected.isEmpty()) {
            return;
        }
        for (GroupMessage m : affected) {
            if (m.getSenderId() == null || m.getSenderId().equals(readerId)) {
                continue; // 读者自己读自己的消息不通知
            }
            String countStr = redisTemplate.opsForValue().get(GROUP_MSG_READ_PREFIX + groupId + ":" + m.getSeq());
            int count = 0;
            if (countStr != null) {
                try {
                    count = Integer.parseInt(countStr);
                } catch (NumberFormatException ignored) {
                    // 坏数据按 0
                }
            }
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("conversationId", groupId);
            data.put("seq", m.getSeq());
            data.put("readCount", count);
            downstreamProducer.sendEnvelope(m.getSenderId(), null, DownstreamEnvelope.TYPE_GROUP_READ, data);
            log.info("group read count pushed: group={} seq={} readCount={} to={}", groupId, m.getSeq(), count, m.getSenderId());
        }
    }

    /** 群成员校验(群已读越权防护) */
    private boolean isGroupMember(String groupId, String userId) {
        Long count = groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId));
        return count != null && count > 0;
    }

    /**
     * 群成员发送消息后自动推进自己的已读水位(发送者已看到自己的消息)。
     * 保证"n人已读"的 count-1 语义一致:发送者总是被计入,count-1 = 其他已读人数;
     * 否则发送者刚发消息时水位没覆盖自己 → 界面 count-1 算错(别人读了却显示 0)。
     * 发送者必是成员,无需再校验;同一 Lua 只进不退,重复调用不重计。
     */
    public void advanceGroupReadOnSend(String groupId, String senderId, long untilSeq) {
        redisTemplate.execute(new DefaultRedisScript<>(GROUP_READ_LUA, Long.class),
                List.of(GROUP_READ_PREFIX + groupId + ":" + senderId),
                String.valueOf(untilSeq),
                GROUP_MSG_READ_PREFIX + groupId + ":",
                String.valueOf(GROUP_COUNT_TTL_SECONDS));
    }

    /** 某用户在会话里的已读水位:Redis 实时优先,MySQL 持久化兜底 */
    public long readSeqOf(String conversationId, String userId) {
        if (conversationId == null || userId == null) {
            return 0;
        }
        String v = redisTemplate.opsForValue().get(READ_PREFIX + conversationId + ":" + userId);
        if (v != null) {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException ignored) {
                // 坏数据忽略,走 DB
            }
        }
        ReadPos pos = readPosMapper.selectOne(
                new LambdaQueryWrapper<ReadPos>()
                        .eq(ReadPos::getUserId, userId)
                        .eq(ReadPos::getConversationId, conversationId));
        return pos == null || pos.getReadSeq() == null ? 0 : pos.getReadSeq();
    }

    /**
     * 对端已读水位(拉历史接口用):我在 A#B 里,返回另一边的已读水位。
     * 客户端据此渲染自己消息的已读:我的消息.seq ≤ 对端水位 → 已读。
     */
    public long peerReadSeq(String conversationId, String myUserId) {
        String peer = peer(conversationId, myUserId);
        return peer == null ? 0 : readSeqOf(conversationId, peer);
    }

    /** 会话对端:min#max 里不是我的那个(非参与者返回 null) */
    private String peer(String conversationId, String userId) {
        if (conversationId == null || userId == null) {
            return null;
        }
        String[] parts = conversationId.split("#");
        if (parts.length != 2) {
            return null;
        }
        if (parts[0].equals(userId)) {
            return parts[1];
        }
        if (parts[1].equals(userId)) {
            return parts[0];
        }
        return null;
    }
}
