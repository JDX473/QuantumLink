package com.quantumlink.im.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quantumlink.im.chat.entity.Conversation;
import com.quantumlink.im.chat.entity.Message;
import com.quantumlink.im.chat.mapper.ConversationMapper;
import com.quantumlink.im.chat.mapper.MessageMapper;
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

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 消息服务:消费上行消息的核心业务逻辑。
 *
 * <p>链路:幂等检查(SETNX)→ 落库(事务内分配 seq)→ 回 ACK(STORE)→ 下行推送。
 *
 * <p>幂等双保险:
 * <ol>
 *   <li>Redis SETNX 快速去重(挡 99.9% 重复);</li>
 *   <li>DB 唯一索引 uk(sender_id, client_msg_id) 兜底(Redis 挂了也判得出)。</li>
 * </ol>
 *
 * <p>为什么 seq 在事务内分配:seq 是排序号,不允许重复。用 UPDATE last_seq 行锁,
 * 同一会话串行分配,保证单调;若用 Redis INCR,宕机重启可能重复 seq。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final StringRedisTemplate redisTemplate;
    private final DownstreamProducer downstreamProducer;

    private static final String DEDUP_PREFIX = "im:msg:dedup:";
    private static final long DEDUP_TTL_SECONDS = 7 * 24 * 3600; // 7 天

    /**
     * 处理一条上行消息(消费端调用)。
     *
     * @param payload 客户端消息(已补 sender_id)
     */
    @Transactional
    public void handleUpstream(MessagePayload payload) {
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
            return;
        }

        // ② 确保会话存在,事务内分配 seq
        Conversation conv = ensureConversation(payload.getConversationId());
        Long seq = conv.getLastSeq();

        // ③ 落库
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

        // ④ 回 ACK-STORE 给发送方(经下行 MQ,统一信封)
        sendStoreAck(payload, message.getId(), seq);

        // ⑤ 下行推送:消息推给接收方。把 serverMsgId + seq 填回 payload,
        //    接收方客户端据此排序(seq)和引用消息(serverMsgId)。
        payload.setServerMsgId(message.getId());
        payload.setSeq(seq);
        payload.setServerTime(message.getServerTime());
        downstreamProducer.sendEnvelope(
                payload.getReceiverId(), null,
                DownstreamEnvelope.TYPE_MSG, payload);
    }

    /** 构建会话 ID:min(a,b)#max(a,b),保证 A→B 和 B→A 是同一个会话 */
    public static String buildConversationId(String a, String b) {
        if (a == null || b == null) {
            return a + "#" + b;
        }
        int cmp = a.compareTo(b);
        return cmp <= 0 ? a + "#" + b : b + "#" + a;
    }

    /** 确保会话存在,并取最新 last_seq(事务内)。 */
    private Conversation ensureConversation(String conversationId) {
        Conversation conv = conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getConversationId, conversationId));
        if (conv == null) {
            conv = new Conversation();
            conv.setConversationId(conversationId);
            conv.setLastSeq(0L);
            conv.setLastMsgTime(LocalDateTime.now());
            conversationMapper.insert(conv);
            // 重新取(自增后)
            conv = conversationMapper.selectOne(
                    new LambdaQueryWrapper<Conversation>()
                            .eq(Conversation::getConversationId, conversationId));
        }
        // 事务内自增 last_seq,行锁保证同一会话串行
        conversationMapper.incrementLastSeq(conversationId);
        conv.setLastSeq(conv.getLastSeq() + 1);
        return conv;
    }

    private Message findByIdempotencyKey(String senderId, String clientMsgId) {
        return messageMapper.selectOne(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSenderId, senderId)
                        .eq(Message::getClientMsgId, clientMsgId));
    }

    /** 回 ACK-STORE:携带 server_msg_id + seq,经下行 MQ 给发送方(统一信封) */
    private void sendStoreAck(MessagePayload payload, Long serverMsgId, Long seq) {
        AckPayload ack = new AckPayload();
        ack.setAckType(AckType.STORE);
        ack.setServerMsgId(serverMsgId);
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
