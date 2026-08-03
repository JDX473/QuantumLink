# IM 消息有序性:从踩坑到解决

> 自研 IM(QuantumLink)长连接层消息有序性实录
> 技术栈:Java 17 · Netty · RocketMQ · Redis · MySQL
> 场景:客户端 → Netty 长连接 → RocketMQ → 业务层落库 → 推送给接收方

---

## 一、序言:为什么"消息有序"是 IM 最难的问题之一

IM 系统的核心诉求可以用四个字概括:**不丢、不重、不乱序**。

"不丢"(可靠投递)和"不重"(幂等去重)相对好做——无非是 ACK、重传、幂等键这些套路。真正难的是**不乱序**:A 先发了"你好",后发了"在吗",B 必须先看到"你好"再看到"在吗"。一旦顺序错了,整个会话的上下文就乱了。

为什么难?因为一条消息从 A 发出去到 B 看到,要跨过**无数可能乱序的环节**:

```
A客户端
  → ① Netty 长连接层(接入层):多线程处理,可能乱序
  → ② RocketMQ:异步投递,可能乱序
  → ③ 业务层消费:并发消费,可能乱序
  → ④ 落库:并行落库,可能乱序
  → ⑤ 下行推送:推给 B,可能乱序
```

每一环都"可能乱序",而我们要的是**端到端有序**。这篇文章记录的就是我在这五环上踩过的坑,以及最终如何用一套"会话内 seq + 保序链路"的方案解决。

---

## 二、核心概念:会话内有序,而不是全局有序

在展开踩坑之前,必须先建立正确的世界观。IM 并不需要"全局有序",只需要**会话内有序**。

- **会话(conversation)**:两个用户之间的一个聊天,用 `conversationId` 标识(单聊是 `min(a,b)#max(a,b)`,保证 A→B 和 B→A 是同一个会话)。
- **会话内有序**:同一个会话里,消息必须按发送顺序展示。
- **全局有序**:所有消息、所有会话都要有序——**IM 不需要这个**,也不需要全局唯一的递增序号。

为什么不需要全局有序?因为不同会话之间没有先后关系,把它们排序毫无意义,还会引入"全局互斥"这个性能杀手。微信的 seqsvr 也是**每用户独立 64 位序号空间**,而不是全局一个序列。

**结论:我们要的是一套"会话内单调递增、按发送顺序分配"的序号(seq)。**

---

## 三、第一个坑:在 DB 里用行锁分配 seq(性能杀手 + 有 bug)

### 我最初的方案

我的第一版实现,seq 是在业务层落库时从 MySQL 分配的:

```sql
-- 伪代码
UPDATE im_conversation SET last_seq = last_seq + 1 WHERE conversation_id = ?;
SELECT last_seq FROM im_conversation WHERE conversation_id = ?;
```

后来发现 `UPDATE` 只返回影响行数、拿不到新值,改成 `SELECT ... FOR UPDATE` 锁行:

```java
@Select("SELECT ... FROM im_conversation WHERE conversation_id = #{conversationId} FOR UPDATE")
Conversation selectForUpdate(String conversationId);
```

### 两个致命问题

**问题一:性能差。** `FOR UPDATE` 会锁住该会话的行,同一会话的所有消息写入都串行化。虽然"同一会话串行"本身没错,但这是在**数据库层面**加锁,把 DB 变成了瓶颈——高并发下,热门会话的写入会排队。

**问题二(更严重):它是在错误的架构上打的补丁。** 真正的问题是业务层**并发消费** MQ 导致 seq 分配竞争,我选择用 DB 锁去"硬压住"这个竞争。这是典型的"在错误的位置用错误的手段解决问题"。

### 深挖:并发消费为什么会让 seq 乱

业务层消费 RocketMQ 用的是并发消费(`MessageListenerConcurrently`),同一个会话的两条消息可能被两个消费线程同时处理:

```
线程1: 处理 msg1 → 读到 last_seq=6 → 分配 seq=7 → 落库
线程2: 处理 msg2 → 读到 last_seq=6 → 分配 seq=7 → 落库   ← 撞了!
```

结果:**seq 重复**。而且即便加锁,由于"谁先抢到锁"不可控,seq 分配顺序也和发送顺序不一致。

---

## 四、第二个坑:天真地以为"MQ 串行消费"能保序(吞吐杀手)

### 我的第二个方案

既然并发消费乱序,那我让业务层**串行消费**不就好了?RocketMQ 提供了 `MessageListenerOrderly`(队列级串行)。

> **队列级串行**:同一队列的消息严格 FIFO 串行处理,不同队列并行。

配合"connect 发 MQ 时按会话选同一队列",我得到了看似正确的方案:

```
connect 按会话选队列 → 同一会话进同一队列 → chat 队列内串行消费 → seq 顺序=消费顺序
```

### 被否定的原因

这个方案**在单会话内是对的**,但有两个硬伤:

1. **性能被单队列卡死**:一个队列只有一个消费线程,热门会话(大群、高频聊天)的消息全堆在一个队列里排队,一条慢消息阻塞后面所有消息。整个系统的吞吐被"最热的那个会话"决定。
2. **MQ 只保证"基本有序"**:消息重投、消费者重试、网络抖动,都会在队列内制造短暂乱序。把"有序性"押在 MQ 上,等于押在一个"尽力而为"的承诺上。

这个方案被否定的那一刻,我意识到:**问题的根源不是"该不该串行",而是"seq 该在哪分配"。**

---

## 五、破局:调研微信/钉钉怎么做的

被否定了两次之后,我去调研了业界(微信、钉钉、企业微信)的真实设计。核心发现:

### 1. 接入层(网关)不应该分配 seq

微信的网关(ConnectSvr)是**无状态**的,只做长连接管理和转发。seq 由**独立的发号服务 seqsvr** 分配。为什么?因为网关要水平扩展、支持重连迁移——如果网关持有 seq 状态,重连到新节点就乱了。

### 2. seq 用"号段预分配",不是每次查库

微信 seqsvr 每秒上千万次调用,如果用"每次分配都落盘"的方式,硬盘早就爆了。它的做法是**号段预分配**:

```
内存:cur_seq(已分配的最大值), max_seq(分配上限)
分配:cur_seq++, 若 cur_seq > max_seq, 则 max_seq += 10000 并落盘一次
```

把"每分配一次写一次盘"(10^7 QPS 的 IO)降到"每 1 万个号写一次"(10^3 QPS 的 IO),降低了 4 个数量级。代价是**允许跳号**(重启后首跳会大跳),但保证"递增不回退"。

### 3. seq 按会话/用户维度,不全局

微信给每个用户独立 64 位序号空间,钉钉按用户维度生成位点 PTS。**关键原则:一个会话/用户只能有一个 seq 来源,会话内唯一递增。**

### 4. 客户端"只认服务端 seq"

无论客户端实际收到消息的顺序如何,都**按 seq 排序展示**。乱序到达时,先用 buffer 缓存,等前序补齐再上屏;发现 seq 空洞,主动补拉。这是最后一道兜底。

---

## 六、最终方案:业务层 Redis INCR + 四跳保序链路

### 核心设计

**seq 由业务层从 Redis 集中发号**(`Redis INCR im:conv:seq:{conversationId}`),而不是 DB 锁、也不是接入层本地计数。

为什么 Redis INCR 是对的:
- **原子性**:INCR 是原子操作,同一会话的所有消息(无论哪个线程)都在同一 key 上递增,seq 唯一。
- **集中**:一个会话一个 key,天然的会话级发号器,多端/多节点共享同一序号空间。
- **轻量**:Redis 单次 INCR 亚毫秒,远快于 DB 锁。
- **演进路径**:MVP 用 Redis INCR 足够;后续可演进到"号段预分配"(微信 seqsvr 模式),把逐条 RTT 降到批发式。

### 四跳保序链路(关键!)

但 Redis INCR 本身只保证"唯一且递增",**不保证"递增顺序 = 发送顺序"**。如果消息乱序到达业务层,谁先到 Redis 谁拿小号,seq 顺序就乱了。

所以必须保证**消息按发送顺序到达业务层**,这需要四跳全部保序:

```
① EventLoop 按到达顺序提交(客户端 A 的消息经 TCP 有序到达 Netty,
    EventLoop 按 channelRead 顺序处理,即按发送顺序)
        ↓
② per-conversation 单线程 executor 串行 produce(TCP 保了到达序,
    但 EventLoop 不能阻塞,所以提交到该会话的 FIFO 队列,队列单线程按序发 MQ)
        ↓
③ 按会话选同一 MQ 队列(RocketMQ 同一队列内 FIFO,保证入队顺序=消费顺序)
        ↓
④ chat 队列级串行消费(MessageListenerOrderly,同一队列单线程,按序取号落库)
```

每一跳"保序"都成立,最终 seq 分配顺序 = 发送顺序。**任何一跳缺失,前面的功夫都白费。**

---

## 七、第三个坑(最深):"抢锁顺序 ≠ 到达顺序"

在实现四跳链路时,我踩了一个非常隐蔽、非常典型的坑——它几乎能代表并发编程的所有心酸。

### 直觉上的方案

我在 connect 层用一个 `ConcurrentHashMap<conversationId, Object>` 作为 per-会话锁,`synchronized(lock)` 包裹"发 MQ":

```java
// 直觉方案:per-会话锁 + 同步发 MQ
synchronized (lock) {
    upstreamProducer.send(json, conversationId);  // 同步 send
}
```

### 为什么不对

我以为"同一会话的消息,谁先调 `dispatchUpstream` 谁先进入锁"——但**这是错的**。

`dispatchUpstream` 是从**业务线程池**并发调用的。消息 msg1、msg2 在同一条 TCP 连接上按序到达,`EventLoop` 按序 `submit` 到线程池。但线程池是**多线程并发执行**:

```
线程A: 处理 msg1 → 进入 dispatchUpstream → 尝试抢锁
线程B: 处理 msg2 → 进入 dispatchUpstream → 尝试抢锁
```

**谁先抢到锁,取决于线程调度,而不是谁先被 submit。** 可能 msg2 的线程 B 先抢到锁,先发 MQ,先到 Redis 拿小号——乱序!

`submit` 的顺序(到达顺序)和 `synchronized` 抢锁的顺序,是**两个完全无关的顺序**。这是并发编程里最容易踩的坑:**把"提交顺序"当成"执行顺序"。**

### 正确做法:per-conversation 单线程 executor(FIFO 队列)

不能靠"抢锁",要靠"排队"。正确做法是给每个会话一个**单线程执行器**(FIFO 队列):

```java
// 每个会话一个单线程 executor,消息按到达顺序 submit 进去
ExecutorService executor = conversationExecutors.computeIfAbsent(conversationId,
        k -> Executors.newSingleThreadExecutor());
executor.execute(() -> upstreamProducer.send(json, conversationId));
```

- **EventLoop 上调用 `dispatchFrame`**(保到达顺序)→ `executor.execute`(FIFO 入队)
- **单线程 executor** 按入队顺序(即到达顺序)依次执行 produce
- **不同会话用不同 executor**,互不阻塞,不牺牲吞吐

这才是"按到达顺序 produce"的正确姿势:**到达顺序靠 EventLoop 保证,produce 顺序靠 FIFO 队列保证,两者在提交那一刻对齐。**

---

## 八、第四个坑:异步 send 无法保序

### 坑

一开始我用 `producer.sendAsync(msg, callback)` 异步发送,配合锁/队列。结果发现**乱序依旧**。

### 根因

`sendAsync` 是**异步**的:调用方把消息交给 RocketMQ 的发送线程池就返回了,实际发到 Broker 的时机在回调线程。所以:

- 即使 `dispatchUpstream` 里 `synchronized` 串行调用了 `sendAsync`,也**只保证"发起"串行,不保证"实际发送"串行**。
- 消息 1 的 `sendAsync` 发起后,回调线程还没发完,消息 2 的 `sendAsync` 已经发起,两条消息可能并发发到 Broker → 乱序。

### 解决

**必须用同步 send**(`producer.send(msg, selector, arg, timeout)`),让"发送"这个动作在调用线程同步完成:

```java
// 在 per-conversation 单线程 executor 里同步 send
// 同步 = 上一条发完,才发下一条,顺序可控
boolean ok = producer.send(json, conversationId);
```

同步 send 会阻塞调用线程(这里是会话 executor 线程,不是 EventLoop,所以不伤主流程),但换来了"发送顺序 = 调用顺序 = 到达顺序"。

---

## 九、第五个坑:EventLoop 不能阻塞(保序与性能的平衡)

### 矛盾

保序要求"在到达点做点什么"(比如同步 send),但 Netty 的 EventLoop 是**单线程、非阻塞**的:

- 一个 EventLoop 管着成百上千条连接。
- 在 EventLoop 上同步 send(可能几毫秒),会把该 EventLoop 上**所有连接**都卡住——线程雪崩。

### 直觉错误:把重活丢"全局线程池"

我的第一反应是把消息丢进一个全局业务线程池(`bizExecutor`):

```java
bizExecutor.submit(() -> producer.send(...));  // 全局线程池并发执行
```

**但这破坏了保序**:全局线程池多线程并发,msg1 先 submit 但可能后执行 → 乱序(和"抢锁"同一个坑)。

### 正解:全局线程池 → per-conversation 单线程 executor

既要"不阻塞 EventLoop",又要"保序",唯一解是 **per-conversation 单线程执行器**:

- EventLoop 上只做**轻量分发**(解析出 conversationId,提交到该会话的 executor)——不阻塞。
- 会话 executor 单线程,**串行**做重的活(同步 send)——保序。
- 不同会话不同 executor,并行——不牺牲吞吐。

这是"用空间(每会话一个线程)换保序与吞吐兼得",也是业界标准的"每会话一个 Processor 线程"模型。

---

## 十、最终链路验证

用自研压测客户端连续快速发 5 条消息,验证 seq 分配顺序:

```
=== 发送顺序 vs 确认顺序 ===
发送#1: D-ord-b9656b-1  → seq=1
发送#2: D-ord-b9656b-2  → seq=2
发送#3: D-ord-b9656b-3  → seq=3
发送#4: D-ord-b9656b-4  → seq=4
发送#5: D-ord-b9656b-5  → seq=5
✅ seq 严格递增,且与发送顺序完全一致
```

对比修复前(seq 分配顺序与发送顺序错乱):

```
发送#1 → seq=2
发送#2 → seq=4
发送#3 → seq=3
发送#4 → seq=1
发送#5 → seq=5   ← 乱序,尽管 seq 唯一
```

---

## 十一、完整保序架构图

```
┌──────────────┐    ① EventLoop 按到达顺序提交
│ A 客户端(TCP) │──────────────────────────────┐
└──────────────┘                              ▼
                                     ┌────────────────────┐
                                     │ Netty 接入层        │
                                     │ per-conversation    │
                                     │ 单线程 executor(FIFO)│
                                     │ 同步 send + 按会话选队 │
                                     └────────────────────┘
                                                    │ ② 串行 produce
                                                    ▼
                                     ┌────────────────────┐
                                     │ RocketMQ client2server │
                                     │ 同一会话 → 同一队列    │
                                     └────────────────────┘
                                                    │ ③ 队列内 FIFO
                                                    ▼
                                     ┌────────────────────┐
                                     │ chat 业务层          │
                                     │ MessageListenerOrderly │
                                     │ Redis INCR 取号       │
                                     │ 落库                 │
                                     └────────────────────┘
                                                    │ ④ 队列内有序
                                                    ▼
                                     ┌────────────────────┐
                                     │ B 客户端(接收方)      │
                                     │ 按 seq 归位展示       │
                                     └────────────────────┘
```

**四个保序锚点**:
1. **EventLoop**:TCP 单连接有序,按 channelRead 顺序提交
2. **per-conversation executor**:FIFO 队列,串行 produce
3. **MQ 队列**:同一会话同一队列,队列内 FIFO
4. **chat Orderly**:队列级串行消费 + Redis INCR 取号

**最后一个兜底**:客户端按 seq 排序展示,乱序到达先缓存,发现空洞主动补拉。

---

## 十二、分布式场景:这套设计还能保序吗

一个自然的问题:上面整个方案是**单机**(一个 connect + 一个 chat)下推演和验证的。**多实例部署时,还能保序吗?**

结论:**能,而且在"会话内有序"语义下几乎是天然成立的。** 原因在于:这套设计从一开始就把"顺序锚点"放在了**分布式天然一致的位置**(RocketMQ 队列 + Redis),而不是节点内存。

### 逐环分析:多实例下每一环的行为

| 环节 | 单机假设 | 多实例行为 | 是否保序 |
|------|---------|-----------|---------|
| **seq 发号** | Redis INCR | 多实例共享同一 key,唯一递增 | ✅ |
| **chat 消费** | Orderly 单消费者 | **同 group 队列归属** | ✅ |
| **落库/推送** | 两段式,seq 已绑定 | 并发不破坏(顺序由 seq 承载) | ✅ |
| **客户端展示** | 按 seq 归位 | 最终一致 | ✅ |

### 关键点一:chat 多实例(consumer group 自动保序)

RocketMQ 的消费语义:**同一个消费者组(ConsumerGroup)内,每个队列在任意时刻只被一个实例消费**(负载均衡按队列分,不是按消息争抢)。

```
Topic client2server 有 4 个队列,2 个 chat 实例(同 group):
  实例A: 队列0, 队列1
  实例B: 队列2, 队列3
```

connect 已按会话选队列,所以"同一会话 → 同一队列 → 同一实例串行消费"。**即使多实例,队列内的有序性天然成立,不需要额外设计。**

### 关键点二:connect 多实例(顺序退化为"Broker 接收顺序")

connect 的 per-conversation executor 是**每节点内存**,多节点时同一会话有两个 executor——但它们**不影响最终顺序**:

- seq 是在 **chat 取的**,不是 connect 取的;
- chat 消费的顺序 = **Broker 入队顺序**(确定的总序);
- 多个 connect 并发发到同一队列,Broker 会串行化,给出一个确定顺序——这就是"服务器收到消息的顺序"。

这正是 IM 的共识:**以服务器收到消息的顺序为准**。单机时 per-conversation executor 尽量贴近"用户自然发送顺序",多实例退化为"Broker 接收顺序"——都是确定、合法、各方一致的全序。

### 两个真实边界(面试会追问)

1. **Rebalance 窗口**:chat 扩缩容时队列从实例 A 迁到实例 B,RocketMQ 的 Orderly 消费用**队列锁**保证迁移期间不并发消费同一队列,只是短暂暂停。seq 从 Redis 继续,不回退、无冲突。
2. **Redis 单点(唯一真正的隐患)**:seq 发号在 Redis,若主从切换极端情况下 INCR 可能回退 → seq 重复。业界用 `appendfsync always` / 号段预分配 / 仲裁机制解决。MVP 单 Redis 可接受,但规模化后要演进。

### 小结

**这套设计天然支持分布式保序**,因为:
- 顺序锚点(seq)在 Redis,唯一且递增;
- 队列归属(consumer group)保证同一会话只被一个实例串行消费;
- 两段式设计让 seq 绑定后的并发不破坏顺序。

这也是为什么它从一开始就是对的:**单机能跑,是多实例能跑的局部情况——两者用同一套保序机制。**

---

## 十三、面试问答(建议背熟)

**Q1: 为什么不用全局唯一序号,而是会话内序号?**
> 全局唯一序号需要全局互斥,是性能杀手;且 IM 不同会话之间没有先后关系,排序无意义。微信 seqsvr 就是每用户独立序号空间。我们需要的是"会话内单调递增、按发送顺序分配"。

**Q2: seq 由谁分配?接入层还是业务层?**
> 业务层(独立发号)。接入层要保持无状态、可水平扩展、支持重连迁移,持有 seq 状态会破坏这点(微信网关就是无状态的)。但分配点要尽量早——业界共识是"以服务器收到消息的顺序为准,seq 先于落库/扇出分配"。

**Q3: 用 DB 自增/行锁分配 seq 行不行?**
> 性能差(行锁串行化 + 拿不到新值),且是错误架构上的补丁。应该用 Redis INCR(会话级 key)或号段预分配(微信 seqsvr 模式),它们原子、集中、轻量。

**Q4: 为什么不用 Redis INCR 就行,还要四跳保序?**
> Redis INCR 只保证"唯一且递增",不保证"递增顺序 = 发送顺序"。如果消息乱序到达业务层,谁先到 Redis 谁拿小号,seq 顺序就错了。所以必须保证消息按发送顺序到达业务层——这是四跳保序的意义。

**Q5: 用 MQ 串行消费保序的缺点?**
> 吞吐被单队列卡死,热门会话变热点;MQ 只保证"基本有序",重投/重试会破坏。应该"按会话保序、跨会话并行":每个会话一个队列/一个处理线程。

**Q6: 为什么"抢锁"保不住序?**
> 抢锁顺序取决于线程调度,不等于提交顺序(到达顺序)。要用 FIFO 队列——谁先入队谁先处理,顺序可控。

**Q7: 异步 send 为什么不行?**
> 异步 send 只保证"发起"串行,不保证"实际发送"串行——回调线程并发发到 Broker 就乱序。必须同步 send,让发送在调用线程串行完成。

**Q8: 接入层分配 seq 和业务层分配,哪个好?**
> 大厂主流是业务层/独立发号(微信 seqsvr、企业微信 SeqSvr、钉钉 PTS)。接入层分配离"到达顺序"更近,但网关要有状态、依赖 Redis,且重连/多端会有 seq 失效问题。接入层分配更多见于社区实践而非大厂生产。

**Q9: 客户端收到乱序消息怎么办?**
> 客户端只认服务端 seq:维护 expected_seq,收到 seq==expected 就上屏并冲刷 buffer;seq>expected 说明有空洞,先入 buffer 等前序;seq<expected 视为重复丢弃。超时仍缺,主动按 seq 范围补拉。

**Q10: 跳号(seq 空洞)怎么办?**
> 允许跳号(重试浪费的号、被拒绝的消息),不要求连续。客户端发现空洞,短时等待 + 超时按 seq 范围补拉;服务端可通过"已落库 maxSeq 水位线"帮客户端判断"这个号是永久缺失还是暂时未落库"。

**Q11: 多实例部署时,这套设计还能保序吗?**
> 能,而且近乎天然成立。核心:seq 发号在 Redis(多实例共享唯一递增);chat 多实例用同一 consumer group,RocketMQ 保证"同一队列只被一个实例消费",所以"同一会话→同一队列→同一实例串行"仍成立;connect 多实例时 per-conversation executor 是节点本地的,但顺序锚点在 chat 取号,connect 并发发到同一队列由 Broker 串行化,退化为"服务器接收顺序"——仍是确定全序。真实边界是 Rebalance 窗口(短暂暂停,seq 不回退)和 Redis 单点(主从切换极端回退,需 appendfsync always 或号段预分配)。

---

## 十四、经验总结

1. **先定"顺序由谁定义",再谈实现**。IM 的顺序锚点是"服务端收到的顺序"→ seq,不是客户端时间戳、不是 TCP 到达序、不是 MQ 消费序。

2. **保序要"在源头钉死",不要"在末端补"**。seq 尽量早分配(接近服务器收到点),后续所有环节按 seq 排序即可,而不是等到落库前才定序。

3. **"提交顺序 ≠ 执行顺序"是并发最大的坑**。任何"我先调用了就一定先执行"的假设都是错的。要么用 FIFO 队列,要么用单一执行者,不要用锁。

4. **性能与正确性的平衡,靠"分片/分区"而不是"全局串行"**。按会话分区(每会话一个队列/一个线程),既保序又并行,是 IM 的标准解法。

5. **任何一个下游的乱序,都只能靠客户端兜底**。无论服务端多保序,网络/重传/多端总有意外。客户端"只认 seq、按序归位、空洞补拉"是最后一道防线,必不可少。

---

## 附:相关代码位置(QuantumLink 仓库)

| 环节 | 文件 |
|------|------|
| 协议帧 / payload | `im-common/src/main/java/com/quantumlink/im/common/protocol/` |
| 会话 ID 工具 | `im-common/src/main/java/com/quantumlink/im/common/util/ConversationIdUtil.java` |
| connect 消息分发(per-conversation executor) | `im-connect/.../handler/MessageDispatcher.java` |
| connect 消息处理(EventLoop 提交) | `im-connect/.../handler/MessageHandler.java` |
| connect 上行生产者(按会话选队列 + 同步 send) | `im-connect/.../handler/UpstreamProducer.java` |
| chat 消费(Orderly 串行) | `im-chat/.../mq/UpstreamConsumer.java` |
| chat 取号(Redis INCR) | `im-chat/.../service/MessageService.java` |
| 有序性验证脚本 | `clients/verify-ordering.js` |

---

*本文基于 QuantumLink IM 项目的真实开发过程,数据来自本机验证(Java 17 + Netty 4.1 + RocketMQ 5.3 + Redis)。*
