package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.config.AuthContext;
import com.quantumlink.im.chat.entity.ReadPos;
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

    /** Redis 水位 key 前缀:im:read:{conversationId}:{readerId} → seq */
    private static final String READ_PREFIX = "im:read:";
    /** 水位 TTL(读水位是展示增强,非可靠性锚点;MySQL 是持久化源,过期后读库兜底) */
    private static final long READ_TTL_SECONDS = 7 * 24 * 3600;

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
    public void handleReadReport(ReadReportPayload report) {
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
