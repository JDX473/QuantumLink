# 群聊设计:读扩散 + 按节点聚合推送

> QuantumLink 群聊功能设计实录
> 技术栈:Java 17 · Netty · RocketMQ · Redis · MySQL · Nacos
> 场景:单聊已支持(可靠投递/有序/水平扩展),群聊在此基础上扩展

---

## 一、为什么群聊是"水平扩展的验收"

单聊的可靠投递、有序性、离线拉取都已打通,而且水平扩展后多 connect 节点 + MQ tag 精准投递验证通过。群聊天然是"一对多",它的扩散恰好压在多节点能力上:

- 群成员分散在多个 connect 节点,一条群消息要**精准投到每个成员所在的节点**
- 这正是"查会话表 → 打 tag"机制的放大版

所以群聊不只是加几个表,而是对水平扩展能力的实战检验。

---

## 二、核心选型:读扩散 + 在线推送

### 为什么不用写扩散

| | 写扩散 | 读扩散 |
|---|---|---|
| 存储 | 群消息落 N 份(每成员收件箱) | 群消息落 1 份 |
| 写放大 | 100 人群 = 100 份写 | 1 份写 |
| 读 | 快(读自己收件箱) | 按 seq 位点拉取 |
| 适用 | 大群(>500 人) | 小群 |

**我们选读扩散**:群消息落库一份(`im_group_message`),成员各自维护 seq 位点拉取——与单聊的 `pullMessages(afterSeq)` 完全同构,离线/多端/乱序兜底全部复用。在线推送的扩散靠"按节点聚合"压到节点数级。

### 为什么小群用读扩散(面试点)

> 写扩散的代价是"每条消息 N 份写 + N 份收件箱存储";读扩散的代价是"读时聚合"。小群成员少,写扩散的放大不划算,读扩散的"写一份 + 按位点拉"最简;微信 500 人以上才切写扩散(收件箱),本质是"读写比"的取舍。

---

## 三、核心机制:信封 targets 按节点聚合推送

### 问题:朴素做法是 MQ 扇出爆炸

群有 N 个在线成员,如果每个人一条 MQ 消息:
- 100 人群 = 100 条 MQ(每个成员查一次会话表、发一条)
- 节点越多、群越大,扇出越爆炸

### 解法:chat 端按节点聚合,每节点一条 MQ

```
① 群成员发消息 → 落 im_group_message(1 份)
② chat 查在线成员 → 查会话表定位各自节点 → 按 nodeId 分组:
     19001 节点: [A, C, E]
     19002 节点: [B, D]
③ 每节点一条 DownstreamEnvelope{ targets:[成员列表], tag=nodeId }
④ 各节点消费 → 遍历 targets → ChannelManager.getAll(uid) 推送(多端全推)
```

**扇出对比**:100 人群 2 节点 = **2 条 MQ**,而非 100 条。聚合是投递优化,发生在信封层,不污染消息体。

### 为什么改信封不改消息体(面试点)

| 层 | 语义 | 决策 |
|----|------|------|
| MessagePayload.receiverId | "消息发给谁/哪个群"(业务语义) | 保持单值(群 id) |
| DownstreamEnvelope.targets | "消息投递到哪些连接"(投递元数据) | 扩展数组 |

**聚合是投递优化,只该影响信封**;connect 只解析顶层(投递元数据),不懂业务内容——targets 对它是"顶层数组",零业务认知改动。

---

## 四、群消息链路(完整)

```
群成员 A 发消息
  → connect 按群 conversationId 选队列 + per-群串行执行器(群内保序,与单聊同构)
  → chat Orderly 消费 → GroupService.handleGroupMessage
  → 幂等去重(im:group_msg:dedup:) → 群维度 Redis INCR 取 seq
  → 落库 im_group_message(1 份,读扩散)
  → 在线成员按节点聚合 → 每节点一条 targets 信封
  → 回 ACK-STORE 给发送者(群消息不回 DELIVER)
  → 离线成员上线:GET /api/groups/{gid}/messages?afterSeq= 拉取
```

**群消息不回 DELIVER**(微信群无"对方已送达"),只回 ACK-STORE——省掉一整套群成员送达跟踪。

---

## 五、数据结构

```sql
im_group:            group_id, name, owner_id            -- 群身份
im_group_member:     group_id, user_id, role             -- 群成员(OWNER/MEMBER)
im_group_message:    group_id, sender_id, content, seq   -- 群消息(独立表,分库分表时按 group_id 分片)
```

- 群 seq:Redis INCR `im:group_seq:{groupId}`(与单聊会话 seq 同构)
- 群消息独立表:与单聊表分离,方便后续分库分表时分别设计分片策略

---

## 六、验证结果(全过)

- ✅ 3 人群跨节点(jds@19001, jdx/alice@19002),发 3 条群消息全部实时到达
- ✅ 群 seq 1-4 连续无重复、递增
- ✅ chat 每条群消息只发 1 条 MQ(members=2 聚合)
- ✅ 离线补拉:拉取接口返回全部 4 条
- ✅ 群播消费:`downstream pushed devices=3`(targets 遍历)

---

## 七、踩坑记录

**坑:Java 17 `List.toList()` 返回不可变 List**,`memberIds.remove(senderId)` 抛 `UnsupportedOperationException`,导致扩散代码在"移除发送者"那步崩溃、群播静默不发。

**教训**:扩散逻辑里对成员列表做 remove 等修改,必须用可变 List(`new ArrayList<>()`),`.toList()` 只读。

---

## 八、演进方向

1. **大群写扩散**:>500 人群切收件箱模型(写 N 份),读 O(1)
2. **群成员在线状态**:群列表显示在线成员数(查会话表聚合)
3. **群消息已读**:群已读人数(成员维度位点,量大)
4. **@ 成员 / 群公告**:消息类型扩展
