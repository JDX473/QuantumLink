# CLAUDE.md — QuantumLink IM 项目记忆

## 项目背景

QuantumLink:27 届秋招主项目,Java 后端深度 IM。从 0 写,目标是撑住面试深挖——每个模块能讲清机制、有取舍、可压测。

- 用户:江东旭(27 届,北邮硕士,方向 Java 后端 + AI Agent)
- 技术栈:Java 17、Maven、Netty、RocketMQ、Nacos、Redis、MySQL、Lettuce、MyBatis-Plus
- 远程仓库:`git@github.com:JDX473/QuantumLink.git`
- **分支工作流**:新功能在 `dev` 分支开发 → 验证通过后 merge 到 `main`。当前在 `dev`,开发 Phase 2 可靠投递。

## 分支与提交规矩(必须遵守)

**新功能一律在 `dev` 分支开发,验证通过后 merge 到 `main`。**

**每次提交代码前,必须先更新文档和 README,记录本次项目进展。** 具体:

1. **push 前必做**:更新 `docs/` 下对应模块的文档 + 更新 `README.md`(架构/模块/进展),再提交。不允许"代码推上去了、文档之后补"。
2. **commit message 自解释**:写清"为什么/做了什么",不写空泛的 "update"。
3. **文档随代码同一 commit**:两者进同一个 commit,保证历史可追溯。
4. 每完成一个阶段/里程碑,在 README 的"项目进展"部分追加记录。
5. 开发分支 dev 上提交时,推送 `git push origin dev`;阶段验证通过后 merge 到 main:`git checkout main && git merge dev && git push`。

## 设计决策记录(实现时遵守)

- **协议**:自定义 TCP(定长头+变长体+crc32),非 WebSocket。帧头 `magic(4B)+version(1B)+type(1B)+bodyLen(4B)`+crc32(4B)帧尾,帧头无 seq(消息身份在 body)。粘包拆包用 `LengthFieldBasedFrameDecoder(lengthFieldOffset=6, lengthFieldLength=4, lengthAdjustment=4)`。HANDSHAKE/HANDSHAKE_ACK 握手鉴权;PING/PONG 心跳。
- **身份体系(服务端分配为主)**:user_id(注册分配)、device_id(首次登录分配,区分客户端/多端基础)、server_msg_id(落库生成,消息正式身份)。幂等键 client_msg_id 客户端生成(`device_id+自增`),去重键 = sender_id+client_msg_id。下行带 server_msg_id+seq,不带 client_msg_id。**登录注册**:`POST /api/auth/register` + `POST /api/auth/login`(SHA-256+salt),登录返回 token/deviceId/userId,存 Redis `im:token:` 供 connect 握手校验。
- **三层身份模型**:username(登录名)全局唯一(DB 唯一索引+注册查重),用于登录/搜索/发消息唯一解析;nickname(昵称)可重复仅展示;remark(好友备注)存好友关系表,只对设置者可见。**userId 是不变锚点**,username/nickname/remark 只影响显示名,不影响业务结构。演进:im_user 加 nickname → 建 im_friendship(含 remark)→ 展示按 remark→nickname→username 取。
- **水平扩展(无 gateway,客户端直连)**:多 connect 节点各自订阅自己的 MQ tag(nodeId = `host:port`);Redis 会话表 `im:session:{userId}:{deviceId} → nodeId`;chat 发下行时查会话表定位节点 → 打 nodeId tag 投 server2client → 只有目标节点消费。调度接口 `GET /api/connects` 返回节点列表(application.yml `connect.nodes` 逗号分隔),客户端随机直连。nodeId 必须与会话表值、MQ tag、调度接口地址三处一致。**注意**:杀 java 进程会连带杀掉 RocketMQ namesrv/broker(它们也是 java),须用 `scripts/start-middleware.cmd` 恢复。
- **负载均衡(服务端最少连接 + Nacos 服务发现)**:接入层用**最少连接**而非随机/一致性哈希。**三层职责分离**:Nacos 管实例存在性(connect 启动注册服务 `im-connect`,心跳+健康检查,替代静态 connect.nodes)、Redis 会话表管消息路由、连接数心跳管负载指标(connect 每 1s 把 `ChannelManager.size()` SETEX 到 `im:node:conns:{nodeId}` TTL 3s)。chat 调度接口 = Nacos 健康实例 × Redis 连接数 → **最少连接决策** → 返回 `{address}`,客户端照单直连,节点列表对客户端透明。**为什么接入层不用一致性哈希**:长连接无状态(会话表路由,不粘节点),迁移少的好处用不上;一致性哈希留给有状态数据路由(如群消息按群 id 粘节点)。**Nacos 本机端口偏移**:server.port=8850(HTTP 8850/gRPC 9850/JRaft 7850)——本机 7848(JRaft 默认 server.port-1000)被 wpspdf 残留连接占用,connect 默认 nacos 127.0.0.1:8850。
- **下行消费(关键坑:consumer group 必须按节点独立)**:connect 的 DownstreamConsumer **必须用独立 group**(`im-connect-consumer-{nodeId}`,`:`/`.` 转 `_`,RocketMQ group 名只允许 `[%|a-zA-Z0-9_-]`)。**共享 group 的坑**:同一 group 内队列被分摊,消息落错队列 → 目标节点够不到;tag 过滤发生在"消费者拉取时",但"谁持有队列"由 group 决定——两层组合才完整。**独立 group = 单消费者独享全部队列 + 按 tag 过滤**,消息不丢。**connect 端口用 19001/19002(不在 Windows 动态端口范围 1024-15000 内)**,999x 可能被系统出站连接(svchost→443)占用导致连接冲突。**下行背压(2026-08-07)**:DownstreamConsumer 写前检查 `channel.isWritable()`——弱网连接 outboundBuffer 积压超高水位(默认 64KB)时不可写,跳过本次推送(不提交写任务),防止弱连接无限吃内存拖垮整个进程;消息已落库,客户端重连增量拉取兜底,不丢("推送尽力而为 + 拉取保证正确")。**Netty 的 WRITE_BUFFER_WATER_MARK 只是软水位**(isWritable=false 但数据照样往 outboundBuffer 写,无硬上限),要真隔离必须自己在写前判断,不能依赖 Netty 默认。**线程模型**:下行写必须在 channel.eventLoop() 线程(MQ 消费线程只 `eventLoop().execute()` 提交任务,不直接跨线程写);下行投递层**不保序**(1 个消费者实例 20 个消费线程喂多个 EventLoop,同用户消息可能被不同线程写),顺序由 seq 承载 + 客户端按 seq 排序渲染兜底。
- **群聊(读扩散 + 信封 targets 按节点聚合)**:群消息落 `im_group_message` **一份**(读扩散),成员各自 seq 位点拉取(与单聊同构);在线推送 = chat 查会话表按 nodeId 分组 → **每节点一条 DownstreamEnvelope{targets: 成员列表}**(MQ 扇出 = 节点数而非成员数),connect 遍历 targets 推送(多端全推)。**改信封不改消息体**:receiverId 保持单值(群 id,业务语义),targets 是投递优化(投递元数据)。**群消息不回 DELIVER**,只回 ACK-STORE。群 seq 用 `im:group_seq:{groupId}` Redis INCR。**踩坑**:Java 17 `.toList()` 返回不可变 List,成员列表 `remove(senderId)` 抛 UnsupportedOperationException → 群播静默不发,必须用可变 ArrayList。识别:conversationId 以 `g_` 开头 = 群消息(connect 按群选队列,群内保序自动成立)。
- **压测驱动修复(2026-08-05)**:
  - **per-会话 executor 必须用"共享有界线程池 + hash 路由"**:每会话一个单线程 executor 只建不回收 → 线程数随历史会话无限增长(压测 200 连接 439 线程)。共享池 N 槽(CPU×2)+ `Math.floorMod(convId.hashCode(), N)` 路由:同一会话 hash 到同一线程(保序不变),不同会话碰撞只损失并行度;池大小固定不可扩缩(取模基数变了会破坏路由稳定性)。
  - **会话表查询必须用设备 Set,不用 keys 扫描**:`keys("im:session:{uid}:*")` 是 O(N) 全库扫描 + 阻塞单线程 Redis(压测曾 1 分钟超时)。connect 写时 `SADD im:devices:{uid}` + `SET im:session:{uid}:{dev}`,chat 读时 `SMEMBERS im:devices:{uid}` + 逐个 GET(O(1))。nodeId 三处一致原则不变。
  - **fillSenderProfile 必须走用户缓存**:chat 每条消息查 User 表(压测 chat CPU 57% 的一部分)→ 改 `UserCacheService`(Redis `im:user:{uid}` 只缓存 username/avatarUrl,TTL 10min,改头像 invalidate)。**坑**:不能序列化整个 User(LocalDateTime 字段 JsonUtil 序列化失败),只存 UI 字段。
  - **压测客户端必须 quiet + 多进程**:client-core 的 console.log 在 700 条/秒下阻塞 Node 事件循环(30 连接延迟 6 倍恶化);单 Node 进程压 30 连接是客户端瓶颈,压测须多进程分散。
- **chat 多实例 + 雪花主键(2026-08-05)**:
  - **chat 多实例开箱即用**:RocketMQ 同 group 自动分摊队列、Redis INCR 发号天然并发安全、幂等双保险扛并发、会话表/用户缓存共享。实例稳定后同会话 seq 严格有序(connect 按会话 hash 选队列 + Orderly 串行消费)。**已知边界**:实例增减瞬间 rebalance 可能瞬时乱序(队列消费权切换,两实例并发取号)——生产可接受,客户端按 seq 排序 + 增量拉取自愈。
  - **消息主键必须用雪花(ASSIGN_ID)**:多 chat 实例写 im_message/im_group_message,DB 自增会撞主键。`@TableId(type = IdType.ASSIGN_ID)`(MyBatis-Plus 内置雪花),schema/表去掉 AUTO_INCREMENT。user/device/conversation 低频写保留自增。
  - **serverMsgId 必须 String 下发**:19 位雪花 id 超过 JS Number 安全范围(2^53),Long 下发客户端丢精度(两个不同 id 显示相同、比较错乱)。协议字段 Long → String,`String.valueOf(id)` 下发;服务端 DB 操作(Long.parseLong)在 DeliverAckConsumer。
- **面对面建群(2026-08-05)**:微信式输 4 位数字快速建群。**核心**:Redis `im:f2f:{code}` → groupId,**TTL 5 分钟做时间窗口**(到期自动释放数字,无定时任务);**Lua 原子"查/建"**(先到 CREATE 后到 EXIST,Redis 单线程无竞态);群名自动"面对面建群 {code}",上限 200 人,`addMember` 幂等。**坑**:Spring `DefaultRedisScript` 返回类型必须 `List.class`(Lua multi-bulk),`Object.class` 会误解析成单值导致 action 判断失效。Lua 返回 `{'EXIST'|'CREATE', gid}`。
- **单测与脚本(2026-08-05)**:**单测** 116 个(JUnit5 + Mockito),`mvn test` 全过;JaCoCo 绑定 3 个模块 pom。**范围**:纯逻辑/业务类全覆盖(工具/编解码/服务层/Controller),**不测**纯 DTO/实体/基础设施(Netty 服务器/RocketMQ 生产消费者/MinIO/启动类)——测了是测框架 mock。业务类指令覆盖 83%。**快速启动脚本**:`scripts/start-all.cmd`(服务端一键)+ `scripts/start-client.cmd`(客户端一键)。**cmd 脚本硬编码坑**(全踩过):① 编码必须 GBK/纯 ASCII(UTF-8 被 cmd 按 GBK 误读成乱码命令);② Electron 启动必须带 app 路径 `.`(否则 "To run a local app" 提示);③ `findstr` 正则管道里不可靠,简化 `findstr ":port"`;④ `if...() 多行块` 偶发解析异常,用 `goto` 跳转;⑤ `start` 标题必须带引号;⑥ bash 调 cmd 的引号会坏,须独立 cmd 进程验证。
- **头像(MinIO)**:注册可选头像(`/api/auth/register/avatar` multipart)、改头像(`/api/users/{userId}/avatar`),im_user.avatar_url。**消息下行带 senderName+senderAvatar,UI 只显示头像+名字不暴露 userId**(内部仍带 senderId)。配置 key 用 `minio.accessKey`(不是 access-key,避免与系统环境变量 MINIO_ACCESS_KEY 冲突)。MinIO 本地 F:\Study\MinIO,新数据目录 quantumlink-data,凭证 minioadmin。
- **单聊已读(2026-08-06)**:可靠投递第三态(存储→送达→已读)。**核心**:已读是**派生状态**——发送方用"对端水位"推导(自己消息.seq ≤ 对端水位 = 已读),**不写共享的 im_message 行**(A#B 共享,逐条标已读分不清方向);水位 = "读到 seq X = ≤X 全已读"(seq 会话内单调,O(1) 表达)。**存储**:Redis `im:read:{conv}:{reader}`(**Lua 原子单调推进**,只进不退,防多端/乱序回退)+ MySQL `im_read_pos(user_id, conv_id, read_seq)` 持久化(UPSERT + GREATEST;**坑**:row alias `AS new` 下未限定列名报 ambiguous,用 `VALUES()` 兼容形式)。**触发**:客户端打开会话/会话中收新消息渲染后上报(`READ_ACK` 帧 {conversationId, untilSeq})→ connect 转发 `read_report` topic → `ReadService.handleReadReport` 推进水位 → 推 READ 事件给对端(在线秒级)。**离线兜底**:拉历史接口返回 `peerReadSeq`(对端水位)——实时事件管当下、拉取接口管历史,对端离线期间读的靠下拉补回,不丢。**协议**:`FrameType.READ_ACK(9)` + `DownstreamEnvelope.TYPE_READ`(connect 映射为 MSG_ACK 帧推给发送方);`ReadReportPayload` 双向复用(上行 {convId,untilSeq} + 下行 {convId,readerId,untilSeq})。**客户端**:渲染器维护 `peerReadSeqByConv`,openConversation 下拉后按对端水位渲染已读 + 上报本端水位;onRead 事件只进不退地更新并重渲染。**群聊已读未做**(读扩散下需成员水位 + 增量计数,后续单独设计)。**语义边界(水位方案共性)**:已读 = **打开会话的近似,非逐条阅读**——打开会话拉到最近 N 条、上报 maxSeq,更早没拉到的消息也被标已读(微信同:打开会话未读清零,哪怕历史没逐条看);水位用"一个数字"换 O(1) 状态/查询,代价是无法表达"精确到条的部分阅读",IM 会话场景不需要,需要精确的阅读类场景才用逐条标记。**坑(实测)**:Redis 重启会清零 `im:conv:seq:{conv}`/`im:group_seq:{gid}` 计数器,而 MySQL 旧消息还在 → 新消息 seq 从 1 回绕、≤ 对端已读水位 → 上报被 Lua 判"未推进"忽略 → 已读不触发(界面卡"已存储")。**修复**:`scripts/reconcile-seq.js` 把计数器校准到 DB 最大 seq(Redis 重启后跑一次)。**教训**:验证时勿随意重启 Redis。
- **已读客户端竞态 + 增量打开 + 状态显示(2026-08-06 实测修复)**:
  - **竞态(DELIVER 晚于 READ)**:DELIVER(对方已送达)与 READ(对方已读)走**两个 topic、两个消费者**(DeliverAckConsumer/ReadAckConsumer),到达顺序不保证——连发消息时 DELIVER 偶发晚于 READ,旧 `onDelivered` 无条件置"已送达"把"已读"降级(症状:最后一条变已送达)。**ACK 恒先于 READ**(实测,消息先存储才能被读),"READ 先于 ACK"仅是兜底保险。**修复**:客户端消息状态**只进不退**(sending<stored<delivered<read),`updateMessageStatus` 按 rank 判定;`onAck` 时用对端水位补判已读。
  - **打开会话改增量**:原 `openConversation` 单次 `afterSeq=0&limit=50` 拉全量,会话 >50 条时最新消息被截断(症状:重进会话消息丢失)。**修复**:会话内缓存 `convMessages`(实时消息按 serverMsgId 去重进缓存),首次打开 `pullTail` 只拉**最近 50 条**(对齐微信:打开看最近,历史靠向上翻),重开只 `pullAfter(cursor)` 增量拉游标之后——与 `afterSeq` 增量同步架构(conversationLastSeq/_pullOffline)一致,不全量。
  - **UI 只标自己消息的状态**:会话里每个用户只看自己发的消息状态(发送中/已存储/对方已送达/对方已读),对方消息 `status=''` 只显示时间(对齐微信/Discord)。
- **多端(2026-08-08)**:持久 deviceId + 设备管理 + 多端全推。**持久 deviceId**:客户端首启 `d_ + 16hex`(crypto.getRandomValues)存 localStorage,登录时带 `deviceId` 参数 → 服务端 `AuthService.login(..., clientDeviceId)` 绑定账号(该设备已存在则 updateById 更新 token/活跃,否则 insert)——同一物理设备重装/重登被认成同一台。**schema 关键**:im_device 唯一键必须是 `uk_user_device(user_id, device_id)` 组合,不能全局 `uk_device_id`——持久 deviceId 可被多账号复用(共用电脑),全局唯一会 DuplicateKey。**多端全推**:下行信封 `deviceId` 空 = `ChannelManager.getAll(userId)` 推所有在线设备。**设备管理**:`GET /api/auth/devices`(listDevices:deviceMapper 查 + Redis `im:devices:{uid}` Set + `im:session:{uid}:{dev}` 判在线)。**坑**:`/api/auth/**` 被鉴权拦截器整体放行 → devices 端点 currentUserId null → WebConfig 改为只放行 register/login/register/avatar 三个具体路径,其余 /api/auth/* 需 token。
- **群聊已读(2026-08-07)**:复用单聊 READ_ACK 通道,协议零改动。**难点**:读扩散只有一份消息,没有"每接收者一份"挂已读状态。**模型**:成员水位 `im:group_read:{gid}:{member}`(用户级,多端共享)+ 每消息**预聚合计数** `im:group_msg_read:{gid}:{seq}`;上报时 Lua 原子"水位只进不退 + 区间 INCR"(批量读只做一批原子 INCR,写放大=视口大小;多端/多实例/乱序不重复计数)。**不广播**(推广播=写放大),已读数**按需查**——拉取接口每条消息带 readCount。**存储**:仅 Redis + TTL(计数7天/水位30天,水位TTL>计数TTL防重复计数窗口;Redis丢后成员重开群自愈)。服务端分流:conversationId `g_` 开头 → 群路径(校验 isMember),否则单聊。客户端:reportRead 放开群聊,群消息渲染"n人已读"(自己发的 count-1)。**坑**:groupReadCounts 用 `multiGet` 批量取整页计数(单条 GET 是 O(N) 轮询);Lua 区间循环在 Redis 单线程内,范围=本次新读条数(小)。**修复(2026-08-07)**:① **发送者自动计入**:群成员发消息落库后调 `ReadService.advanceGroupReadOnSend` 推进自己水位——否则刚发的消息发送者没被计数,界面 count-1 误显示 0。② **实时推送只给发送者**:群已读推进时把 `TYPE_GROUP_READ{groupId,seq,readCount}` 只推给受影响消息的发送者(非群广播;区间 ≤ 20 条才推,批量开群读跳过——发送者重开可见);Lua 返回旧水位(cur)而非 1,推送给 notifyAffectedSenders 用。③ **面对面建群 groupId 不一致**:joinByCode 的 Lua 写进 Redis 的新群 id 与 createGroup 内部生成的 id 不同 → im_group 与 im_group_member id 不一致 → 群列表查不到;createGroup 加指定 id 重载,joinByCode 用 Lua 返回的 id 落库。**坑**:connect 节点是独立进程,改了 DownstreamConsumer 的类型映射必须重启 connect(只重启 chat 会 `unknown type: GROUP_READ`)。
- **有序 seq**:服务端落库同一事务内 `UPDATE im_conversation SET last_seq=last_seq+1` 分配;不用 Redis INCR。
- **可靠投递**:双 ACK——**STORE**(chat 落库,可靠锚点)+ **DELIVER**(接收方客户端回 DELIVER_ACK 帧 → connect 转发 deliver_ack topic → chat 更新状态 SENT→DELIVERED → 回 DELIVER 给发送方)。发送方超时同一 client_msg_id 重传(3s 超时、指数退避、上限 6 次)。
- **客户端发送确认机**:pending 表 + 超时重传 + 断线感知(重连后 flush pending)。client_msg_id 用 **UUID**(`crypto.randomUUID()`),全局唯一,无需维护 deviceId/nonce/counter 拼接状态(旧实现 `deviceId+sessionNonce+自增` 是为防客户端重启 clientSeq 归零撞 TTL 内旧 key,UUID 一步解决;碰撞概率 10^-19 量级可忽略,真撞时幂等去重当重传处理,不产生脏数据)。重传带同一 UUID 幂等去重。ACK 必须回带 client_msg_id,客户端才能精确匹配。
- **seq 分配(业务层取号)**:用 **Redis INCR `im:conv:seq:{conversationId}`** 集中发号(不是 DB 自增/锁)。**两段式**:保序段(Orderly 消费:去重+取seq+绑定)串行且极短,顺序在此钉死;并发段(线程池:落库+ACK+推送)全并行——顺序由 seq 承载,后续并发不破坏。保序链路:EventLoop 按到达顺序提交 → per-conversation 单线程 executor 串行 produce(同步 send)→ 按会话选同一 MQ 队列 → chat Orderly 串行取号。关键教训:并发时"抢锁顺序≠到达顺序",必须 FIFO 队列;异步 send 无法保序,必须同步。
- **缓存**:先 MySQL 后 Redis,消息 append-only 不双删,客户端 seq 补拉自愈。
- **离线消息 + 增量拉取**:消息一律先落库;在线推送、离线不推送,上线 `GET /api/conversations/{convId}/messages?afterSeq=` 按 seq 增量拉取(按 seq 拉而非时间,支持断点续拉/多端对齐)。客户端维护 per-conv 位点 lastSeq,重连后补拉。Spring MVC `@PathVariable`/`@RequestParam` 必须显式写参数名(编译没加 -parameters)。
- **心跳**:客户端 10s PING,服务端 IdleState 30s 兜底断连,Redis TTL=30s。
- **MVP 范围**:单聊/单节点/无网关/无群聊/无多端/有 token 鉴权/必须有 RocketMQ。压测后置。
- **架构**:im-common / im-connect(port 9999,Netty) / im-chat(port 8081,Spring Boot) / im-loadtest。connect 与 chat 零代码依赖,只经 MQ+Redis 通信。
- **下行统一信封 DownstreamEnvelope**:`{to, deviceId, type(ACK/MSG), data(对象)}`——投递元数据(to/deviceId/type)与内容(data)分离,connect 只解析顶层、不懂业务内容;data 用嵌套对象(非 JSON 字符串)避免双重转义。deviceId 空 = 多端全推。
- **连接管理**:ChannelManager 用嵌套 Map(`userId → ConcurrentHashMap<deviceId, Channel>`),对应"用户→设备→连接"一对多;并发用 computeIfAbsent。跨节点路由靠 Redis SessionRegistry。**握手成功必须同时 `ChannelManager.add`(本地) + `SessionRegistry.register`(Redis),缺本地注册下行会误判离线。**
- 核心认知:**EventLoop 只做收发,阻塞调用丢业务线程池**(Java 17 无虚拟线程)。Netty 出站写必须用 `channel.writeAndFlush`(ctx.write 会绕过末尾 encoder)。

## 常用命令

```bash
# 构建(用 JDK 17,D:\jdk17)
JAVA_HOME="D:\\jdk17" mvn clean package -DskipTests

# 启动本机中间件(RocketMQ + Redis + Nacos,MySQL 假设已作为服务运行)
scripts/start-middleware.cmd

# 测试
mvn test
```

## 本机中间件(不用 Docker,用户本地已装)

- MySQL 8: `127.0.0.1:3306`, root/123456, 库 `quantumlink`(独立库,与旧项目 `im` 库隔离)
- Redis: `F:\Study\Redis4`(redis-server.exe, 127.0.0.1:6379, 无密码)
- RocketMQ 5.3.1: `F:\Study\RocketMQ\rocketmq-all-5.3.1-bin-release`(namesrv 9876 / broker 10911, 本机直接跑 .cmd)
- Nacos 2.5: `F:\Study\Nacos\nacos`(standalone,端口 8850——本机 JRaft 7848 被 wpspdf 占用故整体偏移,HTTP 8850/gRPC 9850/JRaft 7850;启动 `bin/startup.cmd -m standalone` 或直接 java -Dserver.port=8850)
- 构建需 JDK 17(`D:\jdk17`), 本机默认 JAVA_HOME 是 JDK8

## 权威规格

- MVP 完整设计:见 `docs/总体设计.md`(帧格式、链路、幂等、有序、离线、存储、边界场景、决策记录)。

## 文档结构

- **文档一律用中文文件名**(如 `docs/群聊已读.md`),内容中文;新增文档遵守此约定,不建英文名。
- `docs/总体设计.md` — MVP 设计与实现方案(权威)
- `docs/消息有序性.md` — 消息有序性(Redis INCR + 四跳保序)
- `docs/下行投递.md` — 下行投递(consumer group 共享的坑 + tag 精准投递)
- `docs/群聊设计.md` — 群聊实现记录(读扩散 + 信封 targets 聚合 + 验证 + 踩坑)
- `docs/小群设计.md` — 小群设计思想(读扩散 vs 写扩散取舍 + 面试讲法)
- `docs/群聊已读.md` — 群聊已读(成员水位 + 预聚合计数,按需查询)
- `docs/EventLoop无锁.md` — Netty EventLoop 为什么无锁(单消费者 + MPSC + CAS,无锁与背压区别)
- `docs/鉴权设计.md` — 鉴权设计(一套凭证两协议 + TCP握手/HTTP + 过期/吊销/踢下线 + 连接生命周期 + 攻击面)
- `docs/鉴权完整篇章.md` — 鉴权完整篇章(深度整合:鉴权本质 + 三身份 + 双协议 + 握手深度 + 越权防护 + 过期 + 吊销 + 生命周期 + 攻击面 + JWT对比 + 缺口演进)
- `docs/慢客户端处理方案.md` — 慢客户端处理(有界+降维:水位/停读 + 有界队列 + MQ位点提交 + 主动断连 + 增量拉取补偿)
- `docs/Redis容灾.md` — Redis 挂了怎么办(依赖审计 + 容灾方案)
- `docs/MQ与RPC选型.md` — 为什么用 MQ 不用 RPC(上行有序 + 下行发布订阅)
- `docs/与生产IM对比.md` — 与真实 IM 的对比(核心同构 + 规模工程差距)
- `docs/压测报告1.md` / `docs/压测报告2.md` — 压测报告
- `README.md` — 架构总览 + 模块 + 启动步骤 + 设计决策 + 项目进展
