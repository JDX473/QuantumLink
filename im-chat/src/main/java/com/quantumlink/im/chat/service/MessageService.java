package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.entity.Conversation;
import com.quantumlink.im.chat.entity.Message;
import com.quantumlink.im.chat.entity.User;
import com.quantumlink.im.chat.mapper.ConversationMapper;
import com.quantumlink.im.chat.mapper.MessageMapper;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 消息服务:消费上行消息的核心业务逻辑。
 *
 * <p><b>两段式设计(有序性的关键):</b>
 * <pre>
 * [保序段](Orderly 消费里,按会话串行)         [并发段](线程池,可全并行)
 *   幂等去重(SETNX)        │                   落库(MySQL)
 *   Redis INCR 取 seq      │ → 绑定 seq 后 →    回 ACK-STORE
 *   绑定 seq 到 payload     │                   下行推送(server2client)
 * </pre>
 *
 * <p>为什么这样拆:<b>顺序在"取 seq 绑定"那一刻就已经钉死</b>,之后落库/推送
 * 顺序是乱的也没关系——接收方客户端只认 seq 排序,按 seq 归位后顺序就是对的。
 * 所以保序段必须串行(短、快,亚毫秒),并发段可以全放开(DB 写、MQ 推,耗时)。
 * 这是业务层取号 + 全并行的业界标准做法(微信/钉钉)。
 *
 * <p>幂等双保险:
 * <ol>
 *   <li>Redis SETNX 快速去重(挡 99.9% 重复);</li>
 *   <li>DB 唯一索引 uk(sender_id, client_msg_id) 兜底(Redis 挂了也判得出)。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final DownstreamProducer downstreamProducer;
    private final GroupService groupService;
    private final UserCacheService userCacheService;

    /** 并发段线程池:绑定 seq 后的落库/ACK/推送,可全并行 */
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()));

    private static final String DEDUP_PREFIX = "im:msg:dedup:";
    private static final long DEDUP_TTL_SECONDS = 7 * 24 * 3600; // 7 天

    /** 会话 seq 发号 key(Redis INCR):同一会话的唯一 seq 来源 */
    private static final String CONV_SEQ_PREFIX = "im:conv:seq:";

    /**
     * 保序段:在 Orderly 消费(按会话串行)中调用。
     * 只做"幂等去重 + 取 seq + 绑定",然后提交线程池并发落库/ACK/推送。
     *
     * @param payload 客户端消息(已补 sender_id)
     * @return true=新消息已入队处理;false=重复消息
     */
    public boolean handleUpstream(MessagePayload payload) {
        // 群消息:conversationId 以 g_ 开头(群 id)→ 走群链路(读扩散,独立表)
        if (payload.getConversationId() != null && payload.getConversationId().startsWith("g_")) {
            groupService.handleGroupMessage(payload);
            return true;
        }

        // 服务端计算会话 ID:min(a,b)#max(a,b),保证同一对用户会话稳定(不管谁发起)
        if (payload.getConversationId() == null || payload.getConversationId().isEmpty()) {
            payload.setConversationId(buildConversationId(payload.getSenderId(), payload.getReceiverId()));
        }
        String dedupKey = DEDUP_PREFIX + payload.getSenderId() + ":" + payload.getClientMsgId();

        // ① 幂等检查:SETNX,首次返回 true 继续,重复直接回 ACK(带原 seq)
        Boolean first = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(first)) {
            log.info("duplicate message, skip store: sender={} clientMsgId={}",
                    payload.getSenderId(), payload.getClientMsgId());
            Message existing = findByIdempotencyKey(payload.getSenderId(), payload.getClientMsgId());
            if (existing != null) {
                sendStoreAck(payload, existing.getId(), existing.getSeq());
            }
            return false;
        }

        // ② 取号:Redis INCR 会话级发号。同一会话的消息在 Orderly 消费里串行,
        //    所以取号顺序 = 消费顺序 = 发送顺序 → seq 顺序 = 发送顺序。
        Long seq = redisTemplate.opsForValue().increment(CONV_SEQ_PREFIX + payload.getConversationId());

        // ③ 绑定 seq,提交线程池并发处理(落库/ACK/推送)
        //    保序段到此结束;seq 已钉死,后续并发不会破坏顺序
        asyncExecutor.submit(() -> asyncProcess(payload, seq));
        return true;
    }

    /** 并发段:绑定 seq 后,落库/回 ACK/推送,可全并行 */
    private void asyncProcess(MessagePayload payload, Long seq) {
        try {
            // ① 落库
            Message message = new Message();
            message.setClientMsgId(payload.getClientMsgId());
            message.setConversationId(payload.getConversationId());
            message.setSenderId(payload.getSenderId());
            message.setReceiverId(payload.getReceiverId());
            message.setMsgType(payload.getMsgType() == null ? "TEXT" : payload.getMsgType());
            message.setContent(payload.getContent());
            message.setSeq(seq);
            message.setStatus("SENT");
            message.setServerTime(System.currentTimeMillis());

            try {
                messageMapper.insert(message);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // DB 唯一索引兜底:并发下 SETNX 都通过但 insert 撞唯一键 → 视为重复
                log.info("duplicate by DB unique key: sender={} clientMsgId={}",
                        payload.getSenderId(), payload.getClientMsgId());
                Message existing = findByIdempotencyKey(payload.getSenderId(), payload.getClientMsgId());
                if (existing != null) {
                    sendStoreAck(payload, existing.getId(), existing.getSeq());
                }
                return;
            }

            log.info("message stored: msgId={} conv={} seq={} sender={}",
                    message.getId(), payload.getConversationId(), seq, payload.getSenderId());

            // ② 回 ACK-STORE 给发送方
            sendStoreAck(payload, message.getId(), seq);

            // ③ 下行推送:把 serverMsgId + seq + 发送者资料填回 payload
            //    senderName/senderAvatar 供 UI 显示(头像+名字),不暴露 userId
            payload.setServerMsgId(String.valueOf(message.getId()));
            payload.setSeq(seq);
            payload.setServerTime(message.getServerTime());
            fillSenderProfile(payload);
            downstreamProducer.sendEnvelope(
                    payload.getReceiverId(), null,
                    DownstreamEnvelope.TYPE_MSG, payload);
        } catch (Exception e) {
            log.error("async process error: conv={} clientMsgId={}",
                    payload.getConversationId(), payload.getClientMsgId(), e);
        }
    }

    /** 填充发送者用户名 + 头像(用于 UI 显示,不暴露 userId);走用户资料缓存避免每条查 DB */
    private void fillSenderProfile(MessagePayload payload) {
        try {
            UserCacheService.UserView sender = userCacheService.getUser(payload.getSenderId());
            if (sender != null) {
                payload.setSenderName(sender.getUsername());
                payload.setSenderAvatar(sender.getAvatarUrl());
            }
        } catch (Exception e) {
            log.warn("fill sender profile failed: sender={}", payload.getSenderId(), e);
        }
    }

    /** 构建会话 ID:min(a,b)#max(a,b),保证 A→B 和 B→A 是同一个会话 */
    public static String buildConversationId(String a, String b) {
        return com.quantumlink.im.common.util.ConversationIdUtil.build(a, b);
    }

    private Message findByIdempotencyKey(String senderId, String clientMsgId) {
        return messageMapper.selectOne(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSenderId, senderId)
                        .eq(Message::getClientMsgId, clientMsgId));
    }

    /** 回 ACK-STORE:携带 client_msg_id + server_msg_id + seq,经下行 MQ 给发送方(统一信封) */
    private void sendStoreAck(MessagePayload payload, Long serverMsgId, Long seq) {
        AckPayload ack = new AckPayload();
        ack.setAckType(AckType.STORE);
        ack.setClientMsgId(payload.getClientMsgId());
        ack.setServerMsgId(String.valueOf(serverMsgId));
        ack.setSeq(seq);
        ack.setReceiverId(payload.getReceiverId());
        ack.setConversationId(payload.getConversationId());
        downstreamProducer.sendEnvelope(
                payload.getSenderId(), null,
                DownstreamEnvelope.TYPE_ACK, ack);
        log.info("ACK-STORE sent: sender={} clientMsgId={} serverMsgId={} seq={}",
                payload.getSenderId(), payload.getClientMsgId(), serverMsgId, seq);
    }
}
