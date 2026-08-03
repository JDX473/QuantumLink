# IM MVP 设计与实现方案

> 状态:**设计共识版** — 待用户 review。从 0 写,Java 17 + Maven 单仓库多模块。
> 范围:MVP = 单聊 / 单节点 / 无网关 / 双 ACK / 无群聊 / 无多端 / 有 token 鉴权。
> 协议:**自定义 TCP**(非 WebSocket)。压测后置。远程仓库 git@github.com:JDX473/QuantumLink.git。
> 提交规矩:每次提交代码前,必须更新文档和 README 记录项目进展(写入 CLAUDE.md)。

## 1. MVP 范围(一句话)

**A 发消息给 B,不丢、不重、不乱序,双 ACK 回执,离线能拉回,能压测出数字。**

### 范围内(做)

| 能力 | 模块 | 面试深挖点 |
|------|------|-----------|
| Netty WS 连接层:握手鉴权、心跳、在线状态 | im-connect | EventLoop 为什么不能阻塞 / 心跳周期怎么定 |
| 消息链路:客户端→connect→MQ→chat 落库→MQ→connect→送达 | 全链路 | 链路每跳责任边界 |
| 幂等:客户端 client_msg_id + SETNX + DB 唯一索引双保险 | im-chat | 为什么幂等键要客户端生成 |
| 可靠投递:双 ACK(STORE+DELIVER)+ 超时重传 | im-chat/im-connect | 丢了/重了/顺序怎么办 |
| 有序:会话内 seq + 客户端按 seq 归位 | im-chat/im-connect | 先发的后到怎么办 |
| 离线:消息先落库 + 上线按 seq 增量拉取 | im-chat | 离线消息怎么设计 |
| 存储:MySQL 落库 + Redis 热缓存(先库后缓存) | im-chat | 为什么消息缓存不需要双删 |
| token 鉴权:登录换 token,握手校验 | im-chat/im-connect | token 为什么放 query / 安全边界 |
| 压测客户端出报告 | im-loadtest | "这是不是你写的"实证 |

### 范围外(MVP 后,记为 P2)

- gateway + 多节点集群 / RocketMQ tag 精准投递
- 群聊
- 多端同步
- ES 检索 / 分表 / 冷热存储
- DELIVER 已读(在 MVP 内)与"未读数/会话列表"完整版
- 消息内容加密、图片/文件传输

## 2. 模块划分与端口

```
E:\QIUZHAO\IM
├── pom.xml                   父 POM:BOM 统一版本
├── docker/docker-compose.yml 中间件编排
├── sql/schema.sql            建库建表
├── docs/                     ← 本文档 + 面试话术
├── im-common                WS 协议信封、DTO、工具(纯 Java)
├── im-connect               长连接层(port 9999,Netty WS 服务器)
├── im-chat                  业务层(port 8081,Spring Boot 3)
└── im-loadtest              压测客户端(Netty WS client)
```

依赖方向:`im-common ← gateway/connect/chat/loadtest`;connect 与 chat **零代码依赖**,只经 MQ + Redis 通信。

**端口**:im-connect 9999(WS);im-chat 8081(HTTP 登录/拉取)。MVP 无 gateway,客户端直连 connect。

## 3. 技术栈

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 17 | `<release>17`,无虚拟线程(Loom 在 21) |
| Spring Boot | 3.3.x | im-chat |
| Netty | 4.1.115 | im-connect / im-loadtest |
| rocketmq-client | 5.3.1 | 原生 API,不用 starter |
| MyBatis-Plus | 3.5.7 | im-chat |
| Lettuce | 6.3.x | connect/chat 统一用 |
| nacos-client | 2.5.0 | 连接层注册(MVP 可先不引) |
| jackson | 2.17.2 | 全模块 |
| Lombok | 1.18.34 | 全模块 |

**核心认知(面试点)**:Java 17 无正式虚拟线程(Loom 在 21)。即便升 21,Netty 的 EventLoop 仍不能阻塞。**EventLoop 只做收发,阻塞调用(JSON/MQ/Redis/DB)统一丢业务线程池。**

## 4. 消息协议(im-common 统一信封)

### 4.1 TCP 帧格式(自定义协议,定长头 + 变长体,网络字节序)

```
| magic(4B) | version(1B) | type(1B) | bodyLen(4B) | body(变长 JSON) | crc32(4B) |
```

| 字段 | 长度 | 说明 |
|------|------|------|
| magic | 4B | `0x514E4C43`("QNLC"=QuantumLink),防串线/防错误解析 |
| version | 1B | 协议版本,预留演进 |
| type | 1B | HANDSHAKE / HANDSHAKE_ACK / MSG / MSG_ACK / PING / PONG / ERROR |
| bodyLen | 4B | body 字节数(不含 crc) |
| body | 变长 | JSON(UTF-8),预留换 protobuf |
| crc32 | 4B | 对 header+body 计算,附加在帧尾 |

**帧头为什么没有 msgId/seq**:帧头只负责"帧边界"(magic/version/type/bodyLen),是传输层分帧;消息身份(server_msg_id)、会话序号(seq)、幂等键(client_msg_id)属于**消息体**,由 body 承载。TCP 已保证单连接内有序可靠,帧头不需要应用层 seq(原方案的帧头 seq 冗余,已去掉)。

**粘包拆包**:`LengthFieldBasedFrameDecoder(maxLen, lengthFieldOffset=6, lengthFieldLength=4, lengthAdjustment=4, initialBytesToStrip=0)`(offset= magic4+version1+type1;adjustment= crc 4B),切出 header+body+crc;`ImFrameDecoder` 再校验 crc、还原 `ImFrame`。CRC 不匹配 → 丢弃该帧并计协议错误(可选直接断连)。

**握手流程**(TCP 建连后先鉴权再收发):
1. 客户端 connect 后先发 `HANDSHAKE {token, userId, deviceId}`
2. 服务端校验 token → 回 `HANDSHAKE_ACK {success, reason}` 或 `ERROR` 并关闭
3. 鉴权通过后才允许 MSG/PING;失败直接断开

**心跳**:客户端 10s 发 `PING`,服务端回 `PONG`;服务端 `IdleStateHandler` 30s(3×10s)无数据兜底断连;Redis 会话 TTL=30s(3×心跳)。

### 4.2 帧 body(payload)内容

### 4.2 帧 body(payload)内容

**身份体系(服务端分配为主,4 个标识分层)**:

| 标识符 | 谁生成 | 全局唯一 | 干什么 | 客户端可见 |
|--------|--------|---------|--------|-----------|
| **user_id** | 服务端(注册时分配) | ✅ | 用户身份 | ✅ |
| **device_id** | 服务端(首次登录分配) | ✅ | 设备身份,区分客户端、多端同步基础 | ✅ |
| **server_msg_id** | 服务端(落库时生成) | ✅ | 消息正式身份(ACK 引用/撤回/排查/多端回显) | ✅ |
| **client_msg_id** | 客户端(自增序号,配 device_id) | ✅(device_id+自增) | 幂等重发去重键 | 客户端本地 |

**登录流程(拿到设备身份)**:

```
首次登录: client → server: {username, password, device_type}
          server → client: {device_id: "D-83921", token: "..."}   # 服务端分配,客户端持久化
再次登录: client 带上 device_id
```

**上行 MSG(A → 服务端)**:

```json
{
  "client_msg_id": "D-83921-7",       // device_id + 自增序号,幂等重发去重键
  "conversationId": "A#B",
  "sender_id": "A",
  "receiver_id": "B",
  "msgType": "TEXT",
  "content": "hello",
  "clientTime": 1234567890
}
```

**下行 MSG(服务端 → B 接收方)**:

```json
{
  "server_msg_id": "10231",           // 服务端生成的消息正式身份
  "seq": 5,                           // 服务端生成,会话内单调,排序/接收方去重
  "conversationId": "A#B",
  "sender_id": "A",
  "content": "hello",
  "serverTime": 1234567890
}
```

**为什么这样分层(面试核心)**:

- **区分客户端/多端同步 = device_id**:服务端在登录时分配全局唯一 device_id,客户端持久化。一个用户多个设备各一个 device_id,多端同步、在线状态、踢设备下线都以它为维度。
- **消息身份 = server_msg_id(服务端生成)**:ACK 引用、撤回、客服排查、多端回显都引用它,服务端可控可审计。
- **幂等键 = client_msg_id(客户端,device_id+自增)**:幂等发生在重发——客户端发出→超时未收 ACK→重发,只有客户端能保证重发带同一个键。`device_id` 是服务端分配的全局唯一,所以 `device_id+自增` 确定性全局唯一、无状态风险(重启不撞旧消息)。
- **为什么不用 UUID 当身份**:身份应由服务端分配,服务端无法验证/审计客户端自报的 ID。UUID 只适合当"幂等标记",不适合当"身份"。
- **为什么不需要 Snowflake**:device_id/user_id 低频分配(DB 自增够),server_msg_id 可用 DB 主键,client_msg_id 用 device_id+自增——都不需要分布式全局唯一+趋势递增的生成器,选型克制。

## 5. 消息链路(核心,含可靠投递)

### 5.1 完整时序

```
A客户端(device_id)        im-connect                im-chat业务层                 B客户端
 │ ① send(MSG,client_msg_id,conv,content)      │                           │
 │───────────────────────>│                       │                           │
 │                        │ ② asyncSend 上行MQ     │                           │
 │                        │──────────────────────>│                           │
 │                        │                       │ ③ 幂等检查 SETNX(client_msg_id)│
 │                        │                       │ ④ 写MySQL(事务内分配 seq,生成 server_msg_id)│
 │                        │                       │ ⑤ 落库成功后追加Redis热缓存  │
 │ ⑥ ACK-STORE(带server_msg_id+seq)│               │                           │
 │<───────────────────────│<──────────────────────│                           │
 │                        │                       │ ⑦ B在线? 查本地channel     │
 │                        │                       │ ⑧ 下行推送(带server_msg_id+seq)│
 │                        │──────────────────────>│                           │
 │                        │                       │ ⑨ B客户端收到→回ACK-DELIVER(server_msg_id)│
 │                        │<──────────────────────│                           │
 │                        │                       │ ⑩ 更新状态=已送达           │
 │ ⑪ ACK-DELIVER(对方已送达)│                       │                           │
 │<───────────────────────│<──────────────────────│                           │
```

### 5.2 可靠投递:双 ACK + 超时重传

- **ACK-STORE**:chat 落库成功后回,携带 `server_msg_id + seq`。语义="消息已安全入库,保证不丢"。**可靠锚点是 MySQL**,不是 Redis。
- **ACK-DELIVER**:B 客户端收到消息后回执(引用 server_msg_id),服务端最终推给 A。语义="对方已收到"。B 离线则 A 永远收不到 DELIVER——这是诚实语义,区分"已存储"和"已送达"本身是面试点。
- **发送方超时重传**:A 发消息后本地乐观渲染,3s 未收 STORE → 用**同一 client_msg_id** 重传。服务端按 client_msg_id 幂等去重。
  - 重传间隔:指数退避 + 抖动 1s→2s→4s→8s→15s→30s,上限 6 次(≈1 分钟)。
  - 客户端 UI 显示"发送中→已发送(已存储)→已送达(对方已读)"三态。
- **seq 分配(已定)**:服务端在落库同一事务内 `UPDATE im_conversation SET last_seq=last_seq+1` 后取回;不用 Redis INCR(宕机重启会重复 seq,排序号不允许重复;seq 必须与落库同事务)。Redis INCR+号段是后续优化。前提:上行 MQ 按 conversationId 同队列 + `MessageListenerOrderly` 串行消费。

### 5.3 幂等:SETNX(client_msg_id) + DB 唯一索引双保险

```
chat 处理每条上行消息:
  ① SETNX im:msg:dedup:{sender_id}:{client_msg_id} (TTL 7天)
       → 返回1(首次) → 继续落库
       → 返回0(重复) → 不落库,直接重发 ACK-STORE(带原 server_msg_id+seq)
  ② INSERT im_message
       → 撞唯一索引 uk(sender_id, client_msg_id) → 也当重复,回 ACK(带原 server_msg_id+seq)
  ③ 幂等和落库在同一个消费流程里
```

- **幂等键 = sender_id + client_msg_id**:client_msg_id 客户端生成(`device_id + 自增`),重发带同一个;device_id 是服务端分配的全局唯一,故该键确定性全局唯一、无状态风险(重启不撞旧消息)。
- **server_msg_id(消息身份)由服务端生成**:与幂等键分离。ACK/撤回/排查/多端回显引用 server_msg_id,服务端可控可审计。
- **SETNX 挡掉 99.9% 重复**(Redis 快);**DB 唯一索引兜底**(Redis 挂了也判得出)。两层理由面试官会追问。
- 重复消息**仍然回 ACK**:A 重发正是因为没收到 ACK,不回它就永远重发。

### 5.4 有序性:seq 客户端归位

- **seq 服务端生成**:`im_conversation.last_seq` 事务内原子自增(`UPDATE ... SET last_seq = last_seq + 1` 后取回)。Redis 可做热缓存,DB 为最终事实。
- **同一会话的消息必须串行处理**:上行按 conversationId 哈希到同一 MQ 队列 + `MessageListenerOrderly` 串行消费。
- **客户端按 seq 归位**:维护 per-conv `recvSeq`,乱序到达缓存等待前序;发现空洞触发补拉。

### 5.5 离线消息

- 消息**一律先落库**。B 离线时 connect 查不到 channel → 不推送(消息已在库,不丢)。
- B 上线:`GET /api/convs/{id}/messages?seq={localSeq}&limit=50` 增量拉取 + 未读数。
- 在线推送 + 离线拉取混合模型。

### 5.6 缓存一致性(先库后缓存,不双删)

- **消息是 append-only**:只追加、永不改写,不存在"覆盖竞争"。Redis 用 `ZADD conv:recent:{id} seq→content` 追加,旧消息本来就是合法历史。
- **回填永不污染**:缓存 miss 回源 MySQL 读到的是"截至当前 seq 的快照",只会补齐不会盖回。
- **先 MySQL 后 Redis**:落库成功才追加缓存,Redis 失败只记日志——缓存是**可丢弃的**,正确性由客户端 seq 补拉自愈。
- **为什么不需要双删**:双删/延迟双删是给"可覆盖的 UPDATE 型数据"准备的;IM 主链路是 append-only,一致性上移到协议层(seq 补拉),缓存落后也能自愈。

## 6. 数据模型(MySQL,可靠性锚点)

```sql
im_user(
  id BIGINT PK AUTO_INCREMENT,
  user_id VARCHAR(64) UNIQUE,          -- 服务端分配,用户身份
  username VARCHAR(64) UNIQUE,
  password_hash VARCHAR(128),
  created_at DATETIME
)

im_device(
  id BIGINT PK AUTO_INCREMENT,
  device_id VARCHAR(64) UNIQUE,        -- 服务端分配,设备身份(多端同步基础)
  user_id VARCHAR(64) NOT NULL,
  device_type VARCHAR(32),             -- web / desktop / mobile
  token VARCHAR(128),                  -- 该设备登录凭证
  token_expire BIGINT,
  last_active_at DATETIME,
  INDEX idx_user(user_id)
)

im_conversation(
  id BIGINT PK AUTO_INCREMENT,
  conversation_id VARCHAR(128) UNIQUE,   -- A#B
  last_seq BIGINT DEFAULT 0,             -- 事务内原子自增
  last_msg_id VARCHAR(64),
  last_msg_time DATETIME,
  updated_at DATETIME
)

im_message(
  id BIGINT PK AUTO_INCREMENT,           -- 主键自增,即 server_msg_id
  client_msg_id VARCHAR(128) NOT NULL,   -- 客户端生成(device_id+自增),幂等去重键
  conversation_id VARCHAR(128) NOT NULL,
  sender_id VARCHAR(64) NOT NULL,
  receiver_id VARCHAR(64) NOT NULL,
  msg_type VARCHAR(16),
  content TEXT,
  seq BIGINT NOT NULL,                   -- 服务端生成,会话内单调
  status ENUM('SENT','DELIVERED') DEFAULT 'SENT',
  server_time BIGINT,
  UNIQUE uk_sender_clientmsg(sender_id, client_msg_id),  -- 幂等唯一索引
  INDEX idx_conv_seq(conversation_id, seq),            -- 离线/历史分页
  INDEX idx_receiver_seq(receiver_id, seq)             -- 收件箱
)

-- 群聊 / 多端表:MVP 后
```

## 7. 各模块职责与关键类

### im-common(纯 Java)
- `ImFrame` / `ImFrameEncoder` / `ImFrameDecoder` — TCP 帧定义与编解码(定长头+变长体)
- `MessagePayload` / `AckPayload` / `HandshakePayload` / `PingPongPayload` — payload 类型
- `FrameType` / `MsgType` / `AckType` — 枚举
- `IdUtil`(device_id/user_id/server_msg_id 生成)、`JsonUtil`(ObjectMapper 单例)

### im-connect(port 9999,Netty)
- `NettyConnectServer` — boss/worker 线程数可配,Epoll/Nio 自动切换
- Pipeline:`LengthFieldBasedFrameDecoder → ImFrameDecoder → HandshakeHandler(先鉴权)→ IdleStateHandler → HeartbeatHandler → MessageHandler`
- `HandshakeHandler` — 首帧握手鉴权(token 查 Redis),失败关闭
- `HeartbeatHandler` — 客户端心跳为主 + 服务端兜底,心跳续期 Redis TTL
- `MessageHandler` — **EventLoop 只做收发**,收帧后丢业务线程池(2×CPU)反序列化 → `asyncSend` 上行 MQ;写回用 `channel.eventLoop().execute()`
- `SessionRegistry` — 每设备 key `im:session:{userId}:{deviceId}→nodeMd5`,TTL=180s,心跳续期
- `UserChannelManager` — `userId#deviceId → Channel`(MVP 可先 userId→Channel,多端 P2)

### im-chat(port 8081,Spring Boot 3)
- `AuthController.login` — 用户名密码 → Redis token(TTL,可踢人)
- `MessageController` — 拉历史 / 增量拉取
- `UpstreamConsumer` — 消费上行 MQ:幂等 SETNX → 落库(事务内分配 seq)→ 追加 Redis → 回 ACK-STORE → 触达推送
- `DownstreamProducer` — 查 Redis 会话表 → 打目标节点 tag 投下行 MQ(单节点版可简化直发)
- `SeqGenerator` — 会话内 seq 生成(DB 事务内自增)
- `PushService` — 查本地 channel 推送 / 离线则跳过

### im-loadtest
- 压测客户端(自定义 TCP client):模拟 N 用户建连、按场景收发、统计发送→STORE 端到端 RT/吞吐/错误率、出 CSV。**MVP 后置,不进 MVP。**

## 8. 压测目标与报告四要素(MVP 后置)

> 压测已从 MVP 挪出(用户决定),保留设计备查,Phase 3 时再落地。

- **基线对比**:阻塞版(EventLoop 同步发 MQ)vs 异步版,记录 RT/P99 差异。
- **场景**:连接建连、单聊收发、重连风暴、长连接保活。
- **报告四要素**:服务器规格(CPU/内存/OS/JDK/中间件版本)+ 工具(im-loadtest 参数全列)+ 并发(连接数×速率)+ 时长/结果(吞吐、RT P50/P99/P999、错误率、JVM-GC、CPU)。
- Windows 无 Epoll,标注平台差异(面试话术:生产 Linux Epoll,本地 Nio)。

## 9. 边界场景(面试必考)

| 场景 | 结果 |
|------|------|
| ACK 返回路上丢,A 重发 | SETNX 判重复,不落库,重发 ACK —— 消息只有一条 |
| 落库前宕机 | 消息没入过库,A 重发当新消息处理 |
| 落库后、回 ACK 前宕机 | 库里已有,DB 唯一索引兜底判重复,回 ACK |
| B 离线 | 消息在库不推送;A 拿"已存储";B 上线按 seq 拉取;A 收不到"已送达" |
| MQ 重复投递 | 消费端幂等检查挡掉 |
| Redis 缓存落后于 MySQL | 客户端按 seq 发现空洞 → 补拉自愈 |
| 客户端重启,client_msg_id 自增归零 | 不可能撞旧消息——client_msg_id 前缀是服务端分配的 device_id,唯一性由 device_id 兜底 |

## 10. 分阶段落地(先骨架后深化)

- **Phase 0 脚手架**(0.5 天):git init + 根 POM 5 模块 + docker-compose + schema + README + **CLAUDE.md(提交规矩)**
- **Phase 1 端到端骨架**(3-4 天):im-common(TCP 协议)+ im-connect(握手鉴权+心跳+异步化)+ im-chat(落库+seq)+ 简单客户端;双客户端互通
- **Phase 2 可靠投递深化**(3-4 天):幂等 + 双 ACK + 重传 + seq + 离线拉取(本设计文档即该阶段蓝本)
- **Phase 3 压测调优 + 文档**(后置):im-loadtest + 报告 + 面试话术

> 注意:Phase 1"骨架"也直接按本设计写(含 token 鉴权、幂等、seq),避免返工;只是 Phase 2 把可靠投递各环节做完整、可故障注入验证。

## 11. 决策记录(已确认,实现按此)

1. **协议**:自定义 TCP(定长头+变长体+crc32),非 WebSocket。帧头 `magic(4B)+version(1B)+type(1B)+bodyLen(4B)`(无 seq,消息身份在 body);粘包拆包 `LengthFieldBasedFrameDecoder(offset=6, adjust=4)`;CRC32 帧尾校验。HANDSHAKE 握手鉴权;PING/PONG 心跳。
2. **幂等 client_msg_id**:客户端生成(`device_id + 自增`),幂等去重键 = sender_id + client_msg_id。**消息身份 server_msg_id 由服务端生成**(DB 主键)。**device_id 服务端分配**(区分客户端/多端基础)。不用雪花。
3. **有序 seq**:服务端在落库同一事务内 `UPDATE last_seq=last_seq+1` 分配;不用 Redis INCR(会重复)。
4. **压测**:MVP 后置,先不实现。
5. **RocketMQ**:MVP 必须保留(可靠投递 + connect/chat 解耦 + 有序消费)。
6. **心跳**:客户端 10s PING + 服务端 IdleState 30s 兜底 + Redis TTL=30s。
7. **重传**:3s 超时、指数退避 1→2→4→8→15→30s、上限 6 次。
8. **远程仓库**:`git@github.com:JDX473/QuantumLink.git`,Phase 0 配置。
9. **提交规矩**:每次提交前更新文档与 README,记录项目进展。
