# 为什么用 MQ 不用 RPC:上行有序 + 下行发布订阅

> 一句话结论:**选 MQ 不是因为它"比 RPC 强",而是它恰好满足这个架构的两个硬需求——上行要"会话级有序"、下行是"发布订阅广播"。RPC 做得到,但要自己实现一致性哈希路由 + 节点管理,而 MQ 原生就有。** 异步解耦/削峰填谷/可靠性是附带价值,不是选型主因。

---

## 1. 架构上下文:两层解耦,两条链路

```
客户端(自定义 TCP)
   │
   ▼
im-connect(Netty 长连接层,可多节点)
   │  ┌─────────────────────────────────────┐
   │  │ 上行 client2server(按会话 hash 到同一队列)│
   │  └─────────────────────────────────────┘
   ▼
im-chat(业务层,可多实例)
   │  ┌─────────────────────────────────────┐
   │  │ 下行 server2client(打 tag=目标节点)   │
   │  └─────────────────────────────────────┘
   ▼
im-connect → 目标客户端
```

connect 与 chat **零代码依赖**,只经 MQ + Redis 通信,可独立部署、独立扩缩。这是理解"为什么 MQ"的前提——两条链路的需求完全不同,必须分开看。

## 2. 上行链路(connect → chat):MQ 满足"会话级有序"

### 2.1 代码路径

```
A 发消息 → connect MessageDispatcher(EventLoop 按到达顺序接收)
  → per-conversation 单线程 executor(FIFO 串行 produce)
  → UpstreamProducer.send:MessageQueueSelector 按 conversationId.hashCode() 选同一队列
  → RocketMQ client2server topic
  → chat UpstreamConsumer:MessageListenerOrderly(队列级串行消费,同 group)
  → MessageService.handleUpstream:SETNX 去重 → Redis INCR 取 seq → 绑定
  → 并发段(落库/ACK/推送,全并行)
```

### 2.2 为什么这段必须靠 MQ

**"会话级有序"是一个跨进程的硬需求**:同一会话(A#B)的所有消息,必须被**同一个消费方、按发送顺序**取号(seq 单调)。

MQ 天然给了这个能力:
- **消息按 conversationId hash 到同一队列**(MessageQueueSelector)
- **RocketMQ 保证同一队列 FIFO**
- **Orderly 消费 = 同一队列单消费者串行处理**

于是"会话内取号顺序 = 客户端发送顺序"这个不变式,靠 MQ 的队列语义就建立了,**不用写一行跨进程锁/一致性哈希代码**。

### 2.3 RPC 要自己解决什么(对比)

如果上行用 RPC(connect 直调 chat):
- 同一会话的连续消息**可能打到不同 chat 实例** → 必须自己实现**一致性哈希**,把同一会话固定路由到同一实例
- 该实例还必须持有该会话的**串行处理队列**(不然同实例内并发也乱序)
- 实例增减时,一致性哈希迁移 → 会话归属变动 → 乱序窗口要自己处理

这些都是 MQ 的**队列 + 消费组**语义已经解决的。RPC 做得到,但等于自己重造 MQ 的下半截。

## 3. 下行链路(chat → connect):MQ 满足"发布订阅 + 精准投递"

### 3.1 代码路径

```
chat 要推给 B → DownstreamProducer.sendEnvelope
  → 查 Redis 会话表 im:session:{B}:{dev} → 拿到 B 的节点 Y
  → 打 tag = Y(节点 id)
  → server2client topic
  → 只有订阅了 tag=Y 的 connect 节点消费(per-node 独立 consumer group)
  → DownstreamConsumer:本地查 ChannelManager → 推给 B 的连接
```

### 3.2 为什么这段必须靠 MQ

下行是**一对多广播**:chat 要通知 N 个 connect 节点(群聊按节点聚合,targets 信封)、或者按目标节点精准投递单播。

MQ 的 **Topic + Tag** 天然是发布订阅:
- **Tag = 目标节点**:Broker 端按 tag 过滤,只有目标节点消费,其他节点零开销
- **独立 consumer group(per-node)**:每个节点独享自己的队列,不会抢别人的消息(这是 下行投递 里踩过的坑)

RPC 做下行 = chat 自己管"所有 connect 节点的地址列表 + 逐个调用 + 处理节点挂了"。而 MQ 让 chat **不需要知道任何节点地址**,只需要按"目标节点 id"打一个 tag 扔进 broker——**路由和扇出交给 MQ**。

## 4. 三个常见回答的校准(面试别踩坑)

| 常见回答 | 校准 |
|---|---|
| **"MQ 异步解耦"** | "解耦"对(connect/chat 独立扩缩,契约是 MQ);"异步"要小心——本系统 produce 是**同步发送**(为保序),且 RPC 也能异步。真正区别是**时空解耦**:producer 不关心 consumer 在哪/在不在/几个 |
| **"MQ 削峰填谷"** | 真实但有限——IM 消息要实时到达,不能久等。削峰填谷是**容忍延迟**系统(订单/推送)的主因,IM 不是,别当主因讲 |
| **"MQ 可靠性比 RPC 高"** | 对一半:MQ 有 broker 持久化 + 重投。但本系统第一可靠性是**客户端重传 + MySQL**(MQ 丢一条,客户端超时重传 + SETNX 幂等兜底),MQ 是第二道网 |

## 5. 面试问答

**Q: 那 RPC(gRPC)什么时候反而合适?**
A: 需要**强交互/请求-应答**的路径,比如"拉历史消息"这种一次调用拿结果的,就该用 RPC(HTTP)。MQ 适合**单向、可异步、要广播/保序**的路径;RPC 适合**请求-响应、要强一致结果**的路径。两者并存是对的。

**Q: 你 chat 多实例,上行怎么保证同一会话不被两个实例并发取号?**
A: RocketMQ 同 consumer group 的 Orderly 消费,同一队列被唯一消费者持有 → 同会话串行。加 Redis INCR 原子发号双保险。已知边界是 rebalance 瞬间的瞬时乱序,客户端按 seq 排序 + 增量拉取自愈。

**Q: 为什么下行不打到所有节点而是打 tag?**
A: tag 精准投递让每个节点只收自己的消息,broker 端过滤,其他节点零开销——这是水平扩展能成立的关键(避免广播风暴)。但代价是要有会话表(Redis)告诉 chat"目标在哪个节点",这是"按 tag"路由的前提。

**Q: 一句话总结选型逻辑?**
> 上行要会话级有序(MQ 队列 + orderly 天然给)、下行是发布订阅广播(MQ 的 topic + tag 天然给);RPC 做得到但要自己实现一致性哈希 + 节点管理。所以按需求选 MQ,RPC 留给请求-应答路径(如 HTTP 拉历史)。

---

*关联:有序性全链路见 `docs/消息有序性.md`;下行 consumer group 的坑见 `docs/下行投递.md`;两层解耦架构见 README。*
