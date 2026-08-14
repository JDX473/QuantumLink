package com.quantumlink.im.chat.service;

import com.quantumlink.im.chat.entity.Message;
import com.quantumlink.im.chat.mapper.MessageMapper;
import com.quantumlink.im.chat.mq.DownstreamProducer;
import com.quantumlink.im.common.protocol.DownstreamEnvelope;
import com.quantumlink.im.common.protocol.MessagePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 下行发件箱(outbox):服务端未确认队列,补上"下推丢失"的最后一跳。
 *
 * <p><b>背景</b>:下行推送"尽力而为"(connect 侧背压跳过/写失败/MQ 丢失都会让在线
 * 接收方漏掉消息),而客户端补拉只在重连时做——如果接收方位置已越过丢失的 seq,
 * 补拉够不到,消息在接收方视角永久丢失。业界标准解法是<b>服务端未确认队列</b>:
 * 推过就入箱、收到 DELIVER_ACK 才出箱、超时未确认定时重推(客户端保持"傻"被动,
 * 只负责收到回 ACK,判断全在服务端)。
 *
 * <p><b>结构</b>(Redis,跨 chat 多实例共享):
 * <ul>
 *   <li>zset {@code im:push:outbox}:member=serverMsgId,score=下次检查时间;</li>
 *   <li>hash {@code im:push:outbox:meta}:field=serverMsgId,value={@code attempts,bornMs}。</li>
 * </ul>
 *
 * <p><b>生命周期</b>:push 真发到 MQ → {@link #add}(10s 后首查);DELIVER_ACK 到达
 * (实时推或补拉都算)→ {@link #remove} 出箱。扫描器每 5s 取到期成员:
 * 查 DB 已 DELIVERED → 清理;接收方离线 → <b>保留不计数</b>(位置可能已越过此消息,
 * 删了补拉也够不到——必须等上线重推,对方补拉后回的 ACK 会自然清掉条目);
 * 在线 → 重推 + 指数退避(30s/1m/2m/5m),重推 {@value #MAX_ATTEMPTS} 次后放弃告警;
 * 入箱超 {@value #MAX_AGE_MS}ms(7 天)兜底放弃(与 dedup TTL 同窗口)。
 *
 * <p><b>多实例安全</b>:扫描用 {@code ZPOPMIN} 原子认领成员(每条消息同一时刻只有
 * 一个实例处理),处理完再 ZADD 回去——不会双发;重推是"至少一次"语义,客户端按
 * serverMsgId 去重渲染,重复无害。
 *
 * <p><b>范围</b>:只覆盖单聊(群消息读扩散、无 per-member DELIVER,漏推靠补拉)。
 * 接收方离线时本来就不推(补拉兜底),所以也不入箱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final StringRedisTemplate redisTemplate;
    private final MessageMapper messageMapper;
    private final DownstreamProducer downstreamProducer;
    private final UserCacheService userCacheService;

    /** 发件箱 zset:member=serverMsgId,score=下次检查时间(epoch ms) */
    private static final String OUTBOX_KEY = "im:push:outbox";
    /** 元数据 hash:field=serverMsgId,value="attempts,bornMs" */
    private static final String META_KEY = "im:push:outbox:meta";

    /** 入箱 → 首次检查间隔:10s(给 DELIVER_ACK 正常到达留窗口,正常消息 1s 内就出箱) */
    private static final long FIRST_CHECK_MS = 10_000;
    /** 接收方离线 / MQ 发送失败时的复查间隔(不计数,等对方上线) */
    private static final long RECHECK_MS = 30_000;
    /** 重推次数上限(第 N 次重推后仍未确认 → 放弃 + 告警日志) */
    private static final int MAX_ATTEMPTS = 5;
    /** 入箱最长存活:7 天(接收方永久离线时条目兜底清理,与 dedup TTL 同窗口) */
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    /** 每轮扫描最多处理条数(防单轮吃太久;剩余成员下一轮继续,分数天然有序) */
    private static final int MAX_PER_ROUND = 500;

    /** 重推退避:第 1/2/3/4/5 次重推后的下次检查间隔 */
    private static final long[] BACKOFF_MS = {30_000, 60_000, 120_000, 300_000, 300_000};

    /**
     * 消息入箱:下推<b>真的发出 MQ</b>后调用(接收方离线没推 → 不入箱,补拉兜底)。
     * 重复入箱(异常路径)不覆盖已有元数据,退避进度保留。
     */
    public void add(Long serverMsgId) {
        String id = String.valueOf(serverMsgId);
        redisTemplate.opsForZSet().add(OUTBOX_KEY, id, System.currentTimeMillis() + FIRST_CHECK_MS);
        redisTemplate.opsForHash().putIfAbsent(META_KEY, id, "0," + System.currentTimeMillis());
    }

    /** 消息出箱:DELIVER_ACK 到达(实时推或补拉,接收方已确认收到)。不在箱里 = no-op。 */
    public void remove(Long serverMsgId) {
        String id = String.valueOf(serverMsgId);
        redisTemplate.opsForZSet().remove(OUTBOX_KEY, id);
        redisTemplate.opsForHash().delete(META_KEY, id);
    }

    /**
     * 扫描器:每 5s 一轮,取到期成员逐个处理。
     *
     * <p>用 ZPOPMIN 原子认领(多 chat 实例只有一个拿到),处理完按新分数放回;
     * 拿到手发现最早一条没到期 → 放回并结束本轮(有序,后面只可能更晚)。
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void scan() {
        long now = System.currentTimeMillis();
        int scanned = 0, resent = 0, cleaned = 0;
        try {
            while (scanned < MAX_PER_ROUND) {
                Set<ZSetOperations.TypedTuple<String>> popped = redisTemplate.opsForZSet().popMin(OUTBOX_KEY, 1);
                if (popped == null || popped.isEmpty()) {
                    break; // 空箱
                }
                ZSetOperations.TypedTuple<String> tuple = popped.iterator().next();
                String id = tuple.getValue();
                double nextCheck = tuple.getScore() == null ? now : tuple.getScore();
                if (nextCheck > now) {
                    redisTemplate.opsForZSet().add(OUTBOX_KEY, id, nextCheck); // 没到期,放回
                    break;
                }
                scanned++;
                Outcome outcome = handleExpired(id, now);
                if (outcome == Outcome.RESENT) {
                    resent++;
                } else if (outcome == Outcome.CLEANED) {
                    cleaned++;
                }
            }
        } catch (Exception e) {
            log.error("outbox scan error", e);
        }
        if (scanned > 0) {
            log.info("outbox scan: scanned={} resent={} cleaned={}", scanned, resent, cleaned);
        }
    }

    /** 处理一个到期的发件箱成员。返回值用于统计,业务动作见各分支注释。 */
    private Outcome handleExpired(String idStr, long now) {
        Long serverMsgId;
        try {
            serverMsgId = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            log.warn("outbox bad member: {}", idStr);
            redisTemplate.opsForHash().delete(META_KEY, idStr); // zset 成员已被 pop 走,只需清元数据
            return Outcome.CLEANED;
        }

        Message msg = messageMapper.selectById(serverMsgId);
        if (msg == null) {
            log.warn("outbox message gone, drop entry: serverMsgId={}", serverMsgId);
            remove(serverMsgId);
            return Outcome.CLEANED;
        }
        // 已被确认(实时推回的 DELIVER_ACK,或补拉后回的对账回执)→ 出箱,不重推
        if ("DELIVERED".equals(msg.getStatus())) {
            remove(serverMsgId);
            return Outcome.CLEANED;
        }

        Meta meta = readMeta(idStr);
        // 兜底:入箱超 7 天仍未送达(接收方永久离线)→ 放弃 + 告警,条目不无限堆积
        if (now - meta.born > MAX_AGE_MS) {
            log.error("outbox give up by age: serverMsgId={} receiver={} ageDays={}",
                    serverMsgId, msg.getReceiverId(), (now - meta.born) / 86_400_000);
            remove(serverMsgId);
            return Outcome.CLEANED;
        }
        // 接收方离线:【不删、不计数】。关键:对方位置可能已越过此消息(收到了更新的消息),
        // 删除后重连补拉够不到 = 丢消息;必须保留到对方上线重推。若对方补拉后回了
        // DELIVER_ACK,remove() 会自然清掉,无需重推。
        if (!downstreamProducer.isOnline(msg.getReceiverId())) {
            redisTemplate.opsForZSet().add(OUTBOX_KEY, idStr, now + RECHECK_MS);
            return Outcome.KEPT;
        }
        if (meta.attempts >= MAX_ATTEMPTS) {
            log.error("outbox give up by attempts: serverMsgId={} receiver={} seq={}",
                    serverMsgId, msg.getReceiverId(), msg.getSeq());
            remove(serverMsgId);
            return Outcome.CLEANED;
        }
        // 接收方在线 → 走正常推送路径重推(至少一次:客户端按 serverMsgId 去重渲染)
        boolean sent = resend(msg);
        if (!sent) {
            // MQ 发送失败(或发送瞬间对方下线)→ 视同离线,复查不计数
            redisTemplate.opsForZSet().add(OUTBOX_KEY, idStr, now + RECHECK_MS);
            return Outcome.KEPT;
        }
        int newAttempts = meta.attempts + 1;
        writeMeta(idStr, newAttempts, meta.born);
        redisTemplate.opsForZSet().add(OUTBOX_KEY, idStr, now + backoffMs(newAttempts));
        log.info("outbox resend #{}: serverMsgId={} receiver={} seq={}",
                newAttempts, serverMsgId, msg.getReceiverId(), msg.getSeq());
        return Outcome.RESENT;
    }

    /** 重推:按 DB 行重建 payload(补发送者资料),走 sendEnvelope 正常推送路径 */
    private boolean resend(Message msg) {
        MessagePayload payload = new MessagePayload();
        payload.setClientMsgId(msg.getClientMsgId());
        payload.setConversationId(msg.getConversationId());
        payload.setSenderId(msg.getSenderId());
        payload.setReceiverId(msg.getReceiverId());
        payload.setMsgType(msg.getMsgType());
        payload.setContent(msg.getContent());
        payload.setServerMsgId(String.valueOf(msg.getId()));
        payload.setSeq(msg.getSeq());
        payload.setServerTime(msg.getServerTime());
        try {
            UserCacheService.UserView sender = userCacheService.getUser(msg.getSenderId());
            if (sender != null) {
                payload.setSenderName(sender.getUsername());
                payload.setSenderAvatar(sender.getAvatarUrl());
            }
        } catch (Exception e) {
            log.warn("outbox fill sender profile failed: sender={}", msg.getSenderId(), e);
        }
        return downstreamProducer.sendEnvelope(
                msg.getReceiverId(), null,
                DownstreamEnvelope.TYPE_MSG, payload);
    }

    /** 读元数据 "attempts,bornMs";缺失/损坏时按"0 次重推、刚入箱"处理(自愈) */
    private Meta readMeta(String id) {
        Object v = redisTemplate.opsForHash().get(META_KEY, id);
        if (v == null) {
            return new Meta(0, System.currentTimeMillis());
        }
        String[] parts = String.valueOf(v).split(",");
        try {
            int attempts = Integer.parseInt(parts[0]);
            long born = parts.length > 1 ? Long.parseLong(parts[1]) : System.currentTimeMillis();
            return new Meta(attempts, born);
        } catch (NumberFormatException e) {
            log.warn("outbox bad meta: id={} value={}", id, v);
            return new Meta(0, System.currentTimeMillis());
        }
    }

    private void writeMeta(String id, int attempts, long born) {
        redisTemplate.opsForHash().put(META_KEY, id, attempts + "," + born);
    }

    /** 第 N 次重推后的退避间隔(超出表尾取最后一个) */
    private long backoffMs(int attempts) {
        return BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length) - 1];
    }

    private enum Outcome { KEPT, RESENT, CLEANED }

    private static final class Meta {
        final int attempts;
        final long born;

        Meta(int attempts, long born) {
            this.attempts = attempts;
            this.born = born;
        }
    }
}
