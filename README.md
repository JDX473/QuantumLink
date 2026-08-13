# QuantumLink IM

高并发 IM 即时通讯系统(Java 后端秋招主项目)。从 0 编写,目标是每个模块能讲清机制、有取舍、可压测。

## 架构

```
客户端(自定义 TCP)
   │  HANDSHAKE / MSG / PING / DELIVER_ACK / READ_ACK
   ▼
im-connect(19001/19002,Netty 长连接层)
   │  握手鉴权 · 心跳 · EventLoop 异步化 · per-conversation 保序 · 会话注册
   │  注册 Nacos · 上报连接数(最少连接调度的数据源) · KICK 订阅(Redis Pub/Sub)
   │
   │  RocketMQ(上行 client2server 消息 / client2signal 信令)
   ▼
im-chat(8081,Spring Boot 业务层)
   │  注册登录 · 幂等 · 落库(Redis INCR 取 seq) · 离线拉取 · 双 ACK 回执
   │  单聊/群聊已读 · 多端设备管理 + 踢人 · 群聊 · 最少连接调度
   │  RocketMQ(下行 server2client 消息 / server2signal 信令)
   ▼
im-connect → 目标客户端
```

**两层解耦**:`im-connect`(长连接)与 `im-chat`(业务)**零代码依赖**,只经 RocketMQ + Redis 通信,可独立部署/扩容。

**信令与消息分通道**(2026-08-09):MQ 上下行各拆独立信令 topic——消息走 `client2server`/`server2client`,信令(DELIVER_ACK/READ_ACK/ACK/DELIVER/READ/GROUP_READ)走 `client2signal`/`server2signal`,队列级隔离,信令积压/重试不影响消息投递。**多端踢人**走 Redis Pub/Sub(`im:kick` 频道),不走 MQ。

## 模块

| 模块 | 端口 | 职责 | 状态 |
|------|------|------|------|
| im-common | — | 自定义 TCP 协议帧、DTO、工具 | ✅ 协议编解码+测试通过 |
| im-connect | 19001/19002 | Netty 长连接层:握手鉴权/心跳/保序/上下行分通道(消息+信令)/会话注册/注册Nacos+上报连接数/KICK订阅 | ✅ 端到端打通 |
| im-gateway | 88 | 入口代理(负载均衡+Nacos 路由) | MVP 后置 |
| im-chat | 8081 | 业务层:注册登录/幂等/落库/seq/离线拉取/双ACK/单聊+群聊已读/群聊/多端+踢人/信令分通道/最少连接调度 | ✅ 端到端打通 |
| im-loadtest | — | 压测客户端 | MVP 后置 |
| im-desktop(客户端) | — | Electron 桌面端(TCP 走主进程,UI 走渲染进程) | ✅ 窗口+登录+收发 |

## 技术栈

Java 17 · Maven · Netty 4.1 · RocketMQ 5 · Redis 7 · MySQL 8 · Spring Boot 3 · MyBatis-Plus · Lettuce

## 快速开始

> **☁️ Linux / 云服务器部署**：见 [docs/云部署.md](docs/云部署.md)——原生安装中间件 + 一键启动，
> 命令级、含 8C16G 资源参数，可直接丢给 AI 照做。**本机 Windows 开发**用下面的 start-middleware.cmd。

```bash
# 1. 启动本机中间件(RocketMQ + Redis + Nacos;MySQL 假设已作为 Windows 服务运行)
scripts/start-middleware.cmd

# 2. 构建(本机默认 JDK8,需用 JDK17)
JAVA_HOME="D:\\jdk17" mvn clean package -DskipTests

# 3. 启动业务层
java -jar im-chat/target/im-chat-1.0.0-SNAPSHOT.jar

# 4. 启动长连接层(端口用 19001/19002,避开 Windows 动态端口;默认 9999)
java -Dim.connect.port=19001 -jar im-connect/target/im-connect-1.0.0-SNAPSHOT.jar

# 5. 注册登录拿 token + deviceId(握手时携带)
curl -X POST http://127.0.0.1:8081/api/auth/register \
  -H "Content-Type: application/json" -d '{"username":"alice","password":"pass123"}'
curl -X POST http://127.0.0.1:8081/api/auth/login \
  -H "Content-Type: application/json" -d '{"username":"alice","password":"pass123","deviceType":"desktop"}'

# 6. 用返回的 token/deviceId 连接(见 clients/verify-auth.js 完整示例)
```

## 关键设计决策

| 决策 | 理由 |
|------|------|
| 自定义 TCP 协议 | 粘包拆包/握手/心跳全是可深挖的真实考点;比 WebSocket 硬核 |
| client_msg_id 客户端生成(UUID) | 幂等键必须客户端生成,重发才能带同一个;UUID 全局唯一免维护;消息身份 server_msg_id 由服务端生成 |
| seq 用 Redis INCR 业务层取号 | 会话级集中发号,唯一且递增;配合 per-conversation 保序链路,顺序 = 发送顺序 |
| 双 ACK(STORE/DELIVER) | 区分"已存储"与"对方已送达",覆盖不同故障边界 |
| 先落库后缓存 | 可靠锚点在 MySQL,Redis 可丢弃,客户端 seq 补拉自愈 |
| EventLoop 只收发 | 阻塞调用丢 per-conversation executor,保序且不阻塞 EventLoop |
| 服务端最少连接调度 | 三层职责分离:Nacos 管实例存在性、会话表管消息路由、连接数心跳管负载指标;调度接口返回"该连谁",客户端无感知节点列表 |
| 已读 = 对端水位推导的派生状态 | im_message 是 A#B 共享行,逐条标已读分不清方向;seq 会话内单调使"读到 seq X" O(1) 表达——发送方用对端水位判定自己消息已读;水位存独立 im_read_pos(每读者一行),Redis 实时 + MySQL 持久化,拉历史接口带 peerReadSeq 兜底离线 |

## 项目进展

- **2026-08-03(Phase 0 脚手架)**:Maven 多模块骨架、本机中间件启动脚本、建库建表、README、提交规矩。
- **2026-08-03(Phase 1 协议层)**:im-common 自定义 TCP 协议完成——帧编解码器(ImFrameEncoder/Decoder)、CRC32 校验、payload 类型(握手/消息/回执/错误)、粘包拆包与半包单测(3 个测试全过)。
- **2026-08-03(Phase 1 连接层)**:im-connect 长连接层完成——Netty TCP 服务器、握手鉴权(token 查 Redis)、心跳(PING/PONG + Redis 续期)、EventLoop 异步化(业务线程池)、会话注册、上行 RocketMQ。修复关键 bug:`ctx.writeAndFlush` 会绕过末尾 encoder,须用 `channel.writeAndFlush`。
- **2026-08-03(Phase 1 业务层)**:im-chat 业务层完成——消费 client2server、幂等(SETNX + DB 唯一索引)、落库 + 事务内分配 seq、回 ACK-STORE、下行 server2client。**端到端链路打通**:客户端→connect→MQ→chat→MySQL 落库→ACK,幂等去重验证通过。
- **2026-08-03(连接管理改造)**:ChannelManager 改为**嵌套 Map**(userId → Map&lt;deviceId, Channel&gt;),语义对应"用户→设备→连接"的一对多模型;computeIfAbsent 保证并发安全,新增 getAll/deviceCount/removeAll 按用户维度操作,4 个并发单测通过。
- **2026-08-03(独立数据库)**:新建独立库 `quantumlink`(与旧项目 `im` 库隔离),只含本项目的 4 张表;schema.sql 与 application.yml 已切换,消息落库验证通过。
- **2026-08-03(Phase 1.5 下行推送)**:链路闭合——connect 新增 DownstreamConsumer 消费 server2client,统一下行信封 DownstreamEnvelope(ACK/MSG),多端全推;修复握手漏注册本地 Channel、下行缺 seq 两个 bug。**双向互聊验证通过**:A 发→B 收(带 seq)+ A 收 ACK-STORE。
- **2026-08-03(下行信封重构)**:DownstreamEnvelope 从 `{targetUserId, contentType, bodyJson}` 重构为 **`{to, deviceId, type, data}`**——投递元数据与内容分离,data 用嵌套对象(非 JSON 字符串),避免双重转义;connect 只解析顶层,不懂业务内容。重构后链路验证通过。
- **2026-08-03(Phase 2 可靠投递-客户端重传)**:ACK 加 clientMsgId(客户端精确匹配);客户端发送确认机——pending 表 + 3s 超时重传 + 指数退避(封顶48s/6次) + 断线感知(重连后 flush pending);修复 seq 分配 bug(FOR UPDATE 锁行)、客户端重启回绕(会话随机前缀)、握手误 flush、重连计数误重置。**验证**:断线重传、幂等重传、seq 递增、双向互聊全部通过。
- **2026-08-03(有序性重构:业务层取号)**:seq 分配从 DB FOR UPDATE 改为 **Redis INCR conv_seq:{conversationId}**(业务层集中发号)。保序链路:EventLoop 按到达顺序提交 → **per-conversation 单线程 executor** 串行 produce(同步 send)→ 按会话选 MQ 队列 → chat **MessageListenerOrderly** 队列级串行消费。修复"并发消费导致 seq 乱序"根因(抢锁顺序≠到达顺序,必须 FIFO 队列)。**验证**:同会话连发 5 条,seq 与发送顺序完全一致;双向互聊、断线重传回归通过。
- **2026-08-03(有序性技术文章)**:沉淀 [docs/消息有序性.md](docs/消息有序性.md)——完整记录消息有序性从踩坑(DB锁/MQ串行/抢锁/异步send/EventLoop阻塞)到解决(Redis INCR + 四跳保序)的思考过程,含 10 个面试问答。
- **2026-08-03(两段式设计:绑定seq后并发)**:按"取seq串行、后续并发"重构 MessageService——保序段(Orderly消费:去重+Redis INCR取seq+绑定)极短且串行;并发段(线程池:落库+ACK+推送)全并行。**顺序在取seq时钉死,后续并发不破坏**。验证:5条消息seq=[1,2,3,4,5]与发送顺序一致,双向互聊/断线重传回归通过。
- **2026-08-03(离线消息 + 增量拉取)**:消息先落库,离线不推送;上线按 seq 增量拉取。新增 `GET /api/conversations/{convId}/messages?afterSeq=&limit=` 接口(MessageController + MessageQueryService),按 seq 升序分页返回 + serverMaxSeq 水位线;客户端维护 per-conv 位点,重连后补拉。修复 `@PathVariable` 缺参数名导致 HTTP 500。**验证**:B 离线收 3 条,重连后按 seq 补回,5 条全部到达且 seq 严格递增。
- **2026-08-03(DELIVER 回执:双 ACK 闭环)**:可靠投递第二跳——接收方 B 收到消息自动回 DELIVER_ACK(新增 FrameType.DELIVER_ACK),connect 转发到 `deliver_ack` topic,chat 更新消息状态 SENT→DELIVERED 并回 DELIVER 给 A。**双 ACK 完整**:A 看到"已存储"(STORE)+"对方已送达"(DELIVER)。验证:DB 状态更新为 DELIVERED。修复 Node 端缺 DELIVER_ACK 帧类型定义。
- **2026-08-03(登录注册)**:真实注册登录流程替代手动塞 token——`POST /api/auth/register`(校验用户名唯一)+ `POST /api/auth/login`(密码 SHA-256+salt 校验 → 生成 token + 分配 device_id → 存 Redis `im:token:` → 落 device 表),返回 `{token, deviceId, userId}`。**验证**:注册/登录/握手/互通全流程通过,device_id 与 user_id 服务端分配。
- **2026-08-03(合并到 master)**:dev 分支验证通过的全部功能(可靠投递闭环 + 登录注册)合并到 `master`。之后新功能仍在 `dev` 开发,验证后 merge。
- **2026-08-03(Electron 桌面端)**:新增 `clients/desktop/`——Electron 桌面客户端。TCP 连接在主进程(复用 client-core.js),UI 走渲染进程(IPC + preload)。**报文式消息 UI**:每条消息带元数据头(发送者/seq/时间)+ 送达状态灯(发送中/已存储/对方已送达),深色信号主题(信号青+量子紫)。含登录/注册界面、会话栏、实时链路状态条。**启动**:`cd clients/desktop && npm install && npm start`。
- **2026-08-04(用户名→userId 解析)**:聊天填用户名(可变、好记)自动解析成 userId(不变、服务端分配的身份锚点)再发送。新增 `GET /api/users/resolve?username=` 接口;桌面端发送前先解析。**设计**:userId 是稳定身份,username 可变——用户改名不影响历史消息/会话/设备。修复:桌面端发送时 IPC 返回 Promise 需用 `.then` 拿 clientMsgId,否则 ACK 匹配不到、状态一直"发送中"。
- **2026-08-04(会话列表 UI 重构)**:聊天交互对齐微信/Discord——左侧会话列表(对方用户名+最后消息+时间,点击选中)+ 右侧消息流 + 输入框直接发(无需填接收方);新会话用＋弹窗输入用户名解析建会话。chat 新增 `GET /api/conversations?userId=` 会话列表接口(按消息聚合+解析对方用户名)。**验证**:会话列表接口返回正确(含 peerUsername/lastMessage/时间)。
- **2026-08-04(头像功能 + 不暴露 userId)**:MinIO 对象存储(本地 F:\Study\MinIO,新数据目录 quantumlink-data)。注册带头像(`/api/auth/register/avatar`)、改头像(`/api/users/{userId}/avatar`)、im_user 加 avatar_url、登录返回 avatarUrl。**消息下行带 senderName + senderAvatar,UI 只显示头像+名字,不暴露 userId**(内部仍带 senderId 用于归属)。会话列表带头像。**踩坑**:系统环境变量 MINIO_ACCESS_KEY 残留覆盖配置(改 accessKey key 避开)、MinIO 旧数据目录凭证不符(新目录启动)。**验证**:注册/改头像/MinIO 存储/URL 访问/消息下行带资料 全链路通过。
- **2026-08-04(桌面端消息显示修复)**:修复消息气泡被裁剪成细条——`.message`/`.msg-head`/`.msg-foot` 加 `flex: 0 0 auto`,消息多时不再被 message-stream 的 flex 压缩(曾压缩到 18px 高,body/foot 溢出被 `overflow:hidden` 裁剪)。去除头像内联 `onerror`(CSP 无 unsafe-inline 会拦截,导致带头像消息塌陷),头像 img 加背景色兜底。登录页头像上传字段默认隐藏(仅注册 tab 显示)。
- **2026-08-04(消息状态按实际渲染)**:修复"给离线用户发消息也显示已送达"——`openConversation` 拉历史时原本固定渲染 `delivered`。改为:下拉接口(MessagePageDto.MessageItem)返回 status 字段,客户端按实际状态渲染(`SENT`→已存储 / `DELIVERED`→对方已送达)。**验证**:离线发送的消息 status=SENT,下拉接口返回正确,客户端显示"已存储"。
- **2026-08-04(合并到 master)**:桌面端消息显示修复、头像功能等 dev 验证通过的功能合并到 master。
- **2026-08-04(水平扩展:多节点 + MQ tag 精准投递)**:去掉 gateway(两倍连接不划算),客户端直连。**架构**:多 connect 节点各自订阅自己的 MQ tag;Redis 会话表存 `userId#deviceId → nodeId`;chat 发下行时查会话表定位目标节点 → 打 nodeId tag 投 `server2client` → 只有目标节点消费(Broker 端过滤,非目标节点零开销)。新增 `GET /api/connects` 调度接口(返回节点列表,客户端随机直连);客户端连接前调调度接口选节点。**验证**:2 个 connect(9999/9998),A 连 9999、B 连 9998,互发消息跨节点到达;im-chat 日志显示 ACK 打 tag 9999、MSG 打 tag 9998,各自只被对应节点消费。
- **2026-08-04(最少连接负载均衡 + Nacos 服务发现)**:调度从"静态节点列表 + 客户端随机"升级为**服务端最少连接决策**。connect 启动注册 Nacos 服务 `im-connect`(动态发现 + 健康检查),每 1s 把本地连接数 `ChannelManager.size()` SETEX 到 Redis `im:node:conns:{nodeId}`(TTL 3s);chat 调度接口 = Nacos 健康实例 × Redis 实时连接数 → 返回连接最少的节点,客户端照单直连(节点列表对客户端透明)。**三层职责分离**:Nacos(实例存在性)/ 会话表(消息路由)/ 连接数心跳(负载指标)。**验证**:verify-lb(3 条连 9999 → 选 9998;再 4 条连 9998 → 切回 9999)、杀 9998 自动摘除、跨节点互聊回归通过。**Nacos 本机端口**:server.port=8850(本机 7848 被 wpspdf 占用,JRaft 端口 = server.port-1000,整体偏移)。
- **2026-08-04(修复:多节点下行丢消息——consumer group 共享)**:多 connect 节点共用同一个 RocketMQ consumer group(`im-connect-consumer`),导致"发给 B 节点的消息被 A 节点消费后丢弃"。**根因**:同一 group 内队列被分摊,消息落错队列 → 目标节点够不到;虽然 broker 按 tag 过滤,但过滤发生在"消费者拉取时",而"谁持有队列"由 group 决定。**修复**:每个节点独立 consumer group(group 名带 nodeId,`:`/`.` 转 `_` 符合 RocketMQ 命名规范)——单消费者独享全部队列,必能拉到自己的 tag。**端口变更**:connect 端口 9999/9998 → 19001/19002(999x 落在 Windows 动态端口范围 1024-15000 内,可能被系统出站连接占用冲突)。**验证**:verify-lb 最少连接 + verify-cross-node 跨节点互聊全部通过。
- **2026-08-05(微信式消息 UI + 头像修复)**:消息渲染从"报文式"(气泡上方头像/用户名/seq/时间)改为**微信式**(头像在气泡外侧、自己右对齐对方左对齐、气泡只有内容、状态+时间在气泡下方小字,seq 收进 data 调试)。**头像修复**:① 自己发消息本地渲染没带头像(send 补 senderAvatar);② 重进会话头像丢失——`pullMessages` 拉取接口没填充 senderName/senderAvatar(与下行推送 fillSenderProfile 不一致),现批量查发送者资料填充。**验证**:拉取接口返回 senderName+senderAvatar 正确。
- **2026-08-05(群聊:读扩散 + 按节点聚合推送)**:支持群聊。**核心选型**:读扩散(群消息落 1 份,成员各自 seq 位点拉取,与单聊同构)+ 在线推送。**信封 targets 聚合**:DownstreamEnvelope 加 targets 数组(该节点上的成员列表),chat 查会话表按 nodeId 分组,每节点一条 MQ(100 人群 2 节点 = 2 条而非 100 条),connect 遍历 targets 推送(多端全推)。**群消息不回 DELIVER**(微信群无"已送达"),只回 ACK-STORE。**存储**:新建 im_group / im_group_member / im_group_message(独立表,分库分表时按 group_id 分片)。**验证**:3 人群跨节点(jds@19001, jdx/alice@19002)发 3 条群消息全实时到达,群 seq 连续无重复,离线补拉通过。**踩坑**:Java 17 `.toList()` 不可变 List,成员 remove 抛 UnsupportedOperationException 导致群播静默不发(改可变 List 修复)。
- **2026-08-05(群聊头像修复 + 上传限制)**:① 群消息重进会话头像丢失——群拉取接口返回的 GroupMessage 实体没 senderName/senderAvatar(与单聊头像 bug 同源"拉取接口没填充发送者资料"),新增 GroupMessageItemDto 批量填充发送者资料;② 注册带头像报"Unexpected end of JSON input"——Spring Boot 默认上传限制 1MB,超限返回 500 非 JSON,调大 `spring.servlet.multipart.max-file-size` 到 10MB。**验证**:群消息拉取返回 senderName+senderAvatar,3MB 头像上传成功。
- **2026-08-05(HTTP 鉴权 + 越权防护)**:业务接口从裸奔补上安全底线。**鉴权**:AuthInterceptor 校验 `Authorization: Bearer {token}`(与 TCP 握手**共用同一套 Redis token**,一套凭证两种协议),`/api/auth/**` 放行,其余全拦,401 返回 JSON;客户端 authFetch 自动带 token。**越权防护**:所有接口的当前用户从鉴权上下文(AuthContext)取,**不信任 URL 参数**——拉会话列表忽略传入 userId、拉消息校验会话归属(A#B 参与者)、改头像只能改自己、群主/成员从上下文取、群消息仅成员可拉。**验证**:无 token 401/错 token 401/带 token 200;传别人 userId 被忽略;非会话参与者 forbidden;改别人头像 forbidden。
- **2026-08-05(压测驱动修复:executor 泄漏 / keys 扫描 / 用户资料缓存)**:压测(loadtest)驱动三个设计缺陷修复。① **per-会话 executor 线程泄漏**:每会话一个单线程 executor 永不回收,200 连接 439 线程(压测实锤)→ 改**共享有界线程池 48 槽 + hash 路由**(同一会话 hash 到同一线程保序不变,线程固定不增长)。② **Redis keys 扫描**:`keys("im:session:{uid}:*")` O(N) 全库扫描阻塞单线程 Redis(压测曾 1 分钟超时)→ 改**设备 Set `im:devices:{uid}` + SMEMBERS**。③ **fillSenderProfile 每条查 DB**:chat 每条消息查 User 表(压测 chat CPU 57%)→ 改 **Redis 用户资料缓存 `im:user:{uid}`**(改头像失效)。④ 客户端压测加 quiet 模式(console.log 阻塞)。**压测结论**:链路可靠(消息 100% 落库 0 丢失),30 连接 P50 79-108ms(缓存后 2 倍改善);连接数上限需云服务器(Windows 端口限制)。报告见 docs/压测报告2.md。
- **2026-08-05(chat 多实例 + 雪花主键 + JS 精度修复)**:① **chat 多实例验证通过**:RocketMQ 同 group 自动分摊(8081+8082 双实例都消费);Redis INCR 发号 + 队列 hash 路由保证**实例稳定后同会话 seq 严格有序**;幂等双保险扛并发。**已知边界**:实例增减瞬间(rebalance)可能瞬时乱序(队列消费权切换),生产可接受(客户端按 seq 排序 + 增量拉取自愈)。② **雪花主键**:im_message/im_group_message 主键从 DB 自增改 **MyBatis-Plus ASSIGN_ID(雪花)**,多 chat 实例全局唯一(之前 DB 自增会撞);schema/表去掉 AUTO_INCREMENT。③ **JS 精度修复**:19 位雪花 id 超过 JS Number 安全范围(2^53)导致客户端丢精度 → **serverMsgId 下发改为 String**(字段类型 Long→String,`String.valueOf(id)`),客户端统一字符串处理。**验证**:双实例下跨节点互聊通过、雪花 id DB 无重复、seq 稳定后严格有序。
- **2026-08-05(面对面建群)**:微信式"输 4 位数字快速建群"。**核心机制**:Redis `im:f2f:{code}` 映射 code → 群,**5 分钟 TTL 时间窗口**(到期自动释放数字,无需定时任务);**Lua 原子"查/建"**(Redis 单线程,并发输入同数字只建一个群——先到者 CREATE,后到者 EXIST 复用);群名自动"面对面建群 {code}",200 人上限,加人幂等。**踩坑**:Spring `DefaultRedisScript` 返回类型必须 `List.class`——`Object.class` 会把 Lua multi-bulk 误解析成单值导致 action 判断失效。**验证**:A 建群 → B/C 并发加入同群 → 跨节点实时收群消息 → 重复输入幂等。
- **2026-08-05(单元测试 + 快速启动脚本)**:① **单测 116 个全过**(im-common 28 / im-connect 15 / im-chat 73),JaCoCo 绑定三个模块;业务逻辑类**指令覆盖率 83%**(核心服务 AuthService 97%、MessageQueryService 93%、MessageService 84%、UserCacheService 90%、GroupController 79%);纯 DTO/基础设施类(Netty 服务器/RocketMQ 生产消费者/MinIO)不做单测(测了是测框架 mock,无价值)。② **快速启动脚本**:`scripts/start-all.cmd`(服务端一键启动:检测端口缺什么起什么)+ `scripts/start-client.cmd`(客户端一键启动:检测服务端→装依赖→起 Electron)。**踩坑**:cmd 脚本必须 GBK/纯 ASCII 编码(UTF-8 被 cmd 按 GBK 误读成乱码命令)、Electron 启动必须带 app 路径 `.`(否则显示 "To run a local app" 提示)、`findstr` 正则简化、`start` 标题必须带引号。
- **2026-08-06(单聊已读:可靠投递第三态)**:新增已读回执——B 打开会话/看到新消息时上报"我已读到 seq X",服务端单调推进水位(Redis Lua 原子 + MySQL `im_read_pos` 持久化)并推 READ 事件给 A,A 渲染"对方已读"。**核心设计**:① 已读是**派生状态**——发送方用"对端水位"推导(自己消息.seq ≤ 对端水位 = 已读),**不写共享的 im_message 行**(A#B 共享,逐条标已读分不清方向);② 读水位 O(1) 表达(seq 会话内单调,"读到 seq X" = "≤X 全已读");③ **离线不丢**——拉历史接口带 `peerReadSeq`,实时事件管当下、拉取接口管历史;④ 协议加 `READ_ACK` 帧(9)+ `DownstreamEnvelope.TYPE_READ`,上行走 `read_report` topic,复用 DELIVER_ACK 通道。**验证**:A 发 → B 收 → B 上报 → A 秒收 READ 事件(reader=B untilSeq=1),Redis 水位=1、MySQL read_seq=1、拉历史 peerReadSeq=1 全对(`scripts/verify-read.js`)。
- **2026-08-06(已读修复:状态竞态 + 增量打开 + 状态显示)**:实测发现并修复三个问题。① **DELIVER 晚于 READ 把已读降级**:DELIVER(对方已送达)与 READ(对方已读)走两个 topic/两个消费者,到达顺序不保证——连发消息时 DELIVER 偶发晚于 READ,旧代码无条件置"已送达"把"已读"打回(症状:最后一条变已送达)。修复为客户端消息状态**只进不退**(sending<stored<delivered<read,`updateMessageStatus` 按 rank 判定)+ `onAck` 用对端水位补判;确认 **ACK 恒先于 READ**(消息先存储才能被读,"READ 先于 ACK"仅兜底保险)。② **重进会话消息丢失**:`openConversation` 原单次 `afterSeq=0&limit=50`,会话 >50 条时最新消息被截断——改为**增量打开**:会话内缓存(实时消息按 serverMsgId 去重)+ 首次打开只拉最近 50 条(对齐微信,历史靠向上翻)+ 重开只拉游标之后增量,与 `afterSeq` 增量同步架构一致。③ **UI 只标自己消息的状态**:对方发的消息不显示状态标签(只显示时间),对齐微信/Discord。
- **2026-08-06(clientMsgId 改 UUID)**:幂等键从 `deviceId+会话随机前缀+自增` 拼接改为 **`crypto.randomUUID()`**——全局唯一,删掉 clientSeq/sessionNonce 两套维护状态,重启/多设备/多会话天然不撞(旧拼接靠随机 nonce 防客户端重启后 clientSeq 归零撞 TTL 内去重 key)。serverMsgId(雪花)保留作为服务端正式身份(与微信双 id 同构)。**UUID 碰撞概率 10^-19 量级**(工程上可忽略,低于硬件故障);真撞时幂等去重(SETNX)会当重传处理,不产生脏数据。**验证**:UUID 生成、ACK 回带匹配、同 UUID 重传不重复落库、verify-read 端到端回归全过。
- **2026-08-07(群聊已读:成员水位 + 预聚合计数)**:实现群聊已读——**协议零改动**,复用单聊 READ_ACK 帧 + read_report topic。**核心**:成员水位 `im:group_read:{gid}:{member}`(用户级,多端共享)+ 每消息预聚合计数 `im:group_msg_read:{gid}:{seq}`;上报时 **Lua 原子**"水位只进不退 + 区间 INCR"(多设备/多实例/乱序不重复计数,重复进群不重算);**不广播**(推广播=写放大),已读数**按需查**——拉取接口每条消息带 readCount。**存储**:仅 Redis + TTL(计数 7 天 / 水位 30 天),Redis 丢后成员重开群自愈。服务端 `ReadService.handleReadReport` 按 `g_` 前缀分流(群路径校验 isMember)。客户端放开群聊上报,群消息渲染"n人已读"(自己发的 count-1)。**验证**:A 进群计数=1 → B 进群=2 → A 再进群不重复(仍=2);130 单测全过。设计见 [docs/群聊已读.md](docs/群聊已读.md),验证脚本 scripts/verify-group-read.js。
- **2026-08-07(群已读修复:发送者计数 + 实时推送 + 面对面建群 groupId)**:① **发送者总是被计入**:群成员发消息时自动推进自己的已读水位(`ReadService.advanceGroupReadOnSend`,GroupService 落库后调用)——否则刚发的消息自己没被计数,界面 count-1 会误显示 0(别人读了还显示 0)。② **实时推送只给发送者**:群已读推进时,只把 `TYPE_GROUP_READ {groupId, seq, readCount}` 推给受影响消息的**发送者**(非群广播;区间 ≤ 20 条才推,批量开群读跳过,发送者重开可见)——"都在会话内"时发送方实时看到 n人已读增长。③ **面对面建群 groupId 不一致**:`joinByCode` 的 Lua 把新群 id 写进 Redis,但 `createGroup` 内部又生成不同 id 落库 → im_group 与 im_group_member id 不一致 → 群列表查不到;修复为 `createGroup` 支持指定 id、joinByCode 用 Lua 返回的 id 落库。**验证**:面对面建群进列表 ✅、发送者实时收到 readCount=2(界面显示 1人已读)✅、132 单测全过。
- **2026-08-07(消息有序性文档深挖补全)**:[docs/消息有序性.md](docs/消息有序性.md) 深度补全三处。**① 修正异步 send 乱序机制**(原"异步=回调线程并发就乱序"不准确):快乐路径下同线程顺序调 async 不乱序,真正乱序是**在途窗口 > 1 + 超时/失败重试从 remoting 回调/超时线程在 i+1 已入队之后插队**,附同步/异步时间线对比。**② 第七节同步为当前实现**:per-会话 `computeIfAbsent` 单线程 executor → **共享有界池(CPU×2)+ `floorMod` hash 路由**(压测 439 线程教训;池大小固定不可扩缩,取模基数变会破坏路由)。**③ 新增第十二节"消费端真相"**:`MessageListenerOrderly` 是**多线程**(默认约 20 线程)靠**队列级锁**保序(非单线程),并行度上限 = **min(队列数, 线程数)**(自动建 topic 默认 4 队列 → 保序段仅 4 路并行),不同队列不阻挡、同队列碰撞是"交错"非"乱序",失败重试 `SUSPEND_CURRENT_QUEUE_A_MOMENT` 连坐整队列,线程池槽位与 MQ 队列两个哈希是独立函数。面试问答 Q7 更新 + 新增 Q12。
- **2026-08-07(幂等与取号原子性文档)**:新增 [docs/幂等与取号原子性.md](docs/幂等与取号原子性.md)——沉淀并发追问"幂等判断 + 分配 seq 是不是原子操作、两个相同消息并发会不会发两次"。核心结论:**幂等判断用 SETNX 一步原子**("检查+写入"同一条 Redis 命令,无 TOCTOU 窗口,两个并发 SETNX 必然只有一个成功);**SETNX→INCR 整体不是 Redis 事务,靠 Orderly 单线程串行消费保证流程原子**(同会话同队列同线程,多实例靠队列锁);**三层保险**(SETNX 原子 / Orderly 串行 / DB 唯一索引 `uk_sender_clientmsg`)。诚实边界:单聊 async 窗口(去重查原行可能 null,无害)、**insert 失败但 SETNX 已写 → 重传被挡 7 天(真实缺口,修复方向:失败回滚 dedup 键或缩短 TTL)**、群聊同步路径无 async 窗口。

- **2026-08-08(多端:持久 deviceId + 设备管理 + 多端全推)**:实现多端登录与同步。**持久 deviceId**:客户端首启生成 `d_ + 16hex` 存 localStorage(重装/重登不变),登录时带上 → 服务端绑定账号(已存在则更新 token/活跃时间,否则新建)——同一物理设备被认成同一台,设备管理有稳定标识。**多端登录**:同账号多设备并存(ChannelManager 用户→设备嵌套 Map)。**多端全推**:下行 deviceId 空 = 推给该用户所有在线设备(实时消息每台设备都收到)。**设备管理**:`GET /api/auth/devices` 返回我的设备列表(deviceId/deviceType/在线状态/最近活跃)。**schema**:im_device 唯一键从全局 `uk_device_id` 改为组合 `uk_user_device(user_id, device_id)`——同一物理设备可被多账号使用(共用电脑),device_id 非全局唯一。**坑**:① 持久 deviceId 撞全局唯一键(上一账号已用该 deviceId)→ 改组合唯一键;② `/api/auth/**` 被鉴权拦截器整体放行,devices 端点拿不到 currentUserId → 改为只放行 register/login/avatar。**验证**:两设备(desktop/mobile)同账号在线、设备列表两设备在线、第三方发消息两设备都实时收到、重登复用 d_devA 设备数不增;135 单测全过。
- **2026-08-09(多端踢人 + 心跳修复)**:同端类型单设备模式 + 手动踢设备。**KICK 用 Redis Pub/Sub**(`im:kick` 频道):chat 发布 {userId, deviceId},connect 各节点订阅、**本地判目标**(ChannelManager.get != null 才关,广播只关持有者);即发即弃由"删 token 使重连被拒"兜底。**同端踢人**:登录时 `kickSameTypeDevices` 踢同 deviceType 旧设备(删旧 token + publish KICK,排除正在登录设备);**手动踢**:`POST /api/auth/devices/{deviceId}/kick`。**踢人四步**:删 token(踢死根基)→ publish KICK → connect 在目标 EventLoop 上 remove map + close(close 触发 onDisconnect 清 Redis 会话表)。**心跳修复**:HeartbeatHandler 续期前查会话表,被踢(路由表没了)的连接拒续期 + 关——否则死连接靠心跳续命。**坑**:KickSubscriber 不能在 close 前 clear ConnectionContext(否则 onDisconnect 拿不到 userId,Redis 会话残留,设备列表误判在线)。**验证**:同端 mobile 新登录踢旧(A 断开、设备列表 A 离线/B 在线)、设备踢除端点踢 B(B 断开、离线);139 单测全过。验证脚本 scripts/verify-kick.js。
- **2026-08-09(信令与消息分通道)**:MQ 上下行各拆独立信令通道——消息与信令**队列级隔离**,信令积压/重试不影响消息投递。**上行**:消息 `client2server`,信令(DELIVER_ACK/READ_ACK)合并 `client2signal`(connect 分流发送;chat DeliverAckConsumer/ReadAckConsumer 都订阅 client2signal,靠字段区分 DELIVER/READ)。**下行**:消息 `server2client`,信令(ACK-STORE/DELIVER/READ/GROUP_READ)走 `server2signal`(chat DownstreamProducer 按信封类型分流;connect 两个 DownstreamConsumer 实例分别消费)。**验证**:单聊消息+已读、群已读、群已读实时推送(信令通道)、多端、多端踢人全链路回归通过;139 单测全过。
- **2026-08-13(验证脚本升级到新鉴权流程 + 拉历史鉴权修复)**:云部署后全量回归发现 clients/ 下 10 个早期验证脚本已过时——硬编码 `test-token-123`(旧"Redis 预置 token"假设)与现行"注册→登录→token 握手"不兼容(握手报 AUTH_FAILED),verify-auth 撞 schema.sql 种子用户 alice/bob(dev-only 密码)导致登录失败。**统一升级**:新增 `clients/test-lib.js` 共享测试库(`newUser`/`loginOrRegister`,随机用户名 + 注册兜底登录),10 个脚本全部改为真实注册→登录→token 握手,验证逻辑不变(离线补拉/断线重传/幂等/有序/双 ACK/群聊跨节点/面对面建群等)。**修复 client-core 真实缺口**:`_pullConversation` 拉历史接口的 fetch 没带 `Authorization: Bearer`(历史接口加鉴权后未同步),增量补拉被 401 静默丢弃(桌面端有自己的 authFetch 不受影响,client-core 是测试库所以没被发现)——补上鉴权头。**验证**:clients/ 14 个 + scripts/ 7 个验证脚本 **21/21 全过**。
- **2026-08-13(connect 注册 IP 可配置:跨机器客户端直连)**:云上部署后 connect 节点把 `127.0.0.1` 注册进 Nacos/调度接口,客户端换机器(压测/桌面端在另一台电脑)连不上。**修复**:ConnectConfig 新增 `host` 字段(`im.connect.host` / `IM_CONNECT_HOST`,start-all.sh 透传,默认 127.0.0.1),`nodeId() = config.host:port`——nodeId 是"三处一致"单一锚点(Nacos 注册/会话表值/MQ tag 同源派生),host 一变全局自动跟随。云部署文档将 `IM_CONNECT_HOST=公网IP` 列为**必改**(曾尝试"自动探测本机 IP",但云服务器探测到的是内网 IP,公网客户端依旧连不上——云部署必须显式给公网地址)。**客户端侧全参数化**:verify 脚本 TCP 连接目标统一走 `IM_CONNECT_HOST` 环境变量、HTTP 走 `IM_API`、桌面端 main.js 同支持(默认 API 指向公网 `8.141.86.246:8081`);verify-lb 断言从"完整地址相等"改为"端口相等"(注册 IP 可配置后地址不再固定);verify-async-auth 的 host 参数化。**修复脚本 bug**:start-all.sh `set -u` 下 `IM_NACOS_ADDR` 未设置时报 unbound(先给默认值再裁剪)。**验证**:单测 139 全过、clients/ 14 + scripts/ 7 全量回归通过、Nacos 注册 `8.141.86.246:19001/19002`(healthy)、调度接口返回同地址、跨节点端到端互通、本机 127.0.0.1 默认行为不破坏。
- **2026-08-13(压测数据清理脚本 reset-data.sh)**:压测前的数据重置工具。**设计取舍**:做成**本机 shell 脚本而非 HTTP 接口**——清库接口暴露在公网 = 灾难级风险(任何人可清库),压测清理是运维动作,本机执行零暴露、不污染业务代码。**MySQL + Redis 必须配套清**(CLAUDE.md 教训:只清一边 → seq 与已读水位错位):TRUNCATE 消息/群/已读/会话表 + 按模式删 `im:conv:seq:*`/`im:group_seq:*`/已读/在线 key,保留 `im:token:*`(在线登录态 TTL 自愈,清库不影响已登录客户端)。**RocketMQ 积压不删数据**:用 `mqadmin resetOffsetByTime` 重置消费位点到最新(丢弃积压,必须**先于 TRUNCATE**,否则 chat 还在消费的积压消息会立即把清空的表写回)。**可选项**:`--users`(删压测用户 **lt% 前缀**——同时覆盖 Node 客户端 `lt_r%` 和 Netty 客户端 `lt{idx}_{ts}` 两种命名,连带 im_device/im_group_member)、`-y` 跳过确认。**替换旧脚本**:原 clients/loadtest/loadtest-cleanup.js 是 Windows 专用(硬编码 F:/Study/Redis4)且清 Redis 用 `keys im:*` 全删(会误删在线 token),已废弃。**验证**:默认/--users/--reset-mq 三分支全通;清理后 verify-chat 通过、消息 seq 从 1 重新发号(证明配套清生效)。
- **2026-08-13(reset-data.sh 压测实测发现两个缺陷并修复)**:① **--users 前缀匹配错**:默认 `lt_r%` 只匹配旧 Node 客户端,Netty 压测客户端命名是 `lt{idx}_{ts}`(如 lt0_505400),导致 101 个压测用户残留删不掉——统一默认 `lt%`。② **--reset-mq 只重置 broker 端位点,chat 本地积压缓冲写回**:`mqadmin resetOffsetByTime` 改的是 broker 端消费进度,chat 消费者早已拉取的积压消息不受影响——TRUNCATE 后旧消息继续被消费,**实测写回 9923 条**(还拖累冒烟测试:积压挤占 Orderly 消费,ACK 延迟 2.2s、送达率 39%)。**修复**:--reset-mq 分支在重置位点后**自动重启 chat**(kill + nohup,等端口就绪)——消费者本地缓冲清零,按新位点重新拉取,积压被跳过。修复后实测:清理干净(消息 0、压测用户 0)+ 冒烟测试送达率 100%、P50=17ms。
- **2026-08-13(云服务器本机阶梯压测,报告3)**:腾讯云 8C16G 本机直连 4 轮阶梯压测(Netty 压测客户端):100×3 → 298/s 送达 100% P50 74ms;100×5 → 496/s 99.9% P50 70ms;150×4 → 595/s 99.6% P50 100ms;200×4 → 792/s 99.4% P50 195ms(P90 1.3s 饱和边缘)。**健康水位 ≈600 条/秒(P50<100ms),极限 ≈800 条/秒,13 万+ 条 0 丢失**——预测(600-750 健康)与实际吻合,极限略低(8 核被中间件同机共享)。**公网带宽教训**:同负载公网客户端实测 66.7%/P50 5.4s(带宽打满→TCP 拥塞),本机 100%/86ms——吞吐压测必须本机跑,公网压测测的是链路可用性。报告见 docs/压测报告3.md。
- **2026-08-13(六指标系统性压测,报告4)**:im-loadtest 重构(接收方统计/端到端延迟/空连接 ramp/群扇出)+ 观测工具(observe-lt.sh/bench-route.sh)+ 全矩阵压测。**① 端到端 P99**:健康区 174-182ms(推送段仅 ~5ms,ACK 落库是主要成分)。**② 空连接**:5k/10k/20k/30k 四档握手 100%、0 掉线,每节点 15k 连接 RSS<450MB(-Xmx1g),30k 是档位上限非系统上限;心跳 15s×30k=2000 ops/s blocked=0。**③ 路由**:GET 单连 23k/s/pipeline 1.08M/s,路由本身非瓶颈;群扇出 2×(S-1) 次串行 Redis 是优化空间。**④ 送达/不重**:窗口口径 99.6-100%(尾截断),不重率恒 1.0000,乱序/缺号 O(1);DB 对账 100%。**⑤ TPS 拐点**:健康区 <600/s(P99≤212ms),拐点 600-800/s(P99→1.4s),**硬拐点 800/s 断崖**;崩溃区不丢消息(connect 0 失败/MQ 0 积压/重试 0)。**⑥ 扇出**:10→200 人 ackP99 26→63ms 近线性,扇出倍数精确=成员-1(4 万人投递 0 重复),多群并行无共享瓶颈;带群已读 77 倍放大(100 人 3106ms)——"按需查询"读放大是群已读在活跃大群的真实成本。**数据层**:慢查询 0 条(崩溃区延迟全是排队);dedup key 92 万占 Redis 98%(7 天 TTL 远超分钟级幂等窗口);im_message 索引 15.6MB>数据 11.5MB;无大 value。**方法学**:慢日志 TABLE 模式拖慢 P99 4 倍必须用 FILE;reset/切换后首轮失真 ~1.7s 须预热轮;多档连跑后偶发 1s 失真须重跑验证。报告见 docs/压测报告4.md。

## 分支

- `master`:稳定版,已验证的功能
- `dev`:新功能开发分支(当前)

## 文档

- [docs/总体设计.md](docs/总体设计.md) — MVP 设计与实现方案(权威)
- [docs/消息有序性.md](docs/消息有序性.md) — **IM 消息有序性:从踩坑到解决**(深度技术文章,含 5 个坑 + 保序架构 + **消费端多线程/队列锁与并行度** + **分布式保序** + 12 个面试问答)
- [docs/幂等与取号原子性.md](docs/幂等与取号原子性.md) — **IM 幂等与取号:并发下的原子性**(TOCTOU 拆解 + SETNX 一步原子 + Orderly 串行保证流程原子 + 三层保险 + 诚实边界 + 7 个面试问答)
- [docs/下行投递.md](docs/下行投递.md) — **IM 下行投递:从"时好时坏"到"一个 group 一个节点"**(RocketMQ Topic/Group/Tag 三层机制 + consumer group 共享的坑 + 修复设计 + 11 个面试问答)
- [docs/群聊设计.md](docs/群聊设计.md) — **群聊设计:读扩散 + 按节点聚合推送**(扩散模型选型 + 信封 targets 聚合 + 群消息链路 + 踩坑)
- [docs/Redis容灾.md](docs/Redis容灾.md) — **Redis 挂了怎么办:依赖审计与容灾方案**(Redis 用途分层盘点 + 现状诚实评估 + 发号/路由/去重逐项解决办法 + 大厂定位 + 面试问答)
- [docs/MQ与RPC选型.md](docs/MQ与RPC选型.md) — **为什么用 MQ 不用 RPC:上行有序 + 下行发布订阅**(两条链路逐段分析 + 与 RPC 的对比 + 三个常见回答校准 + 面试问答)
- [docs/与生产IM对比.md](docs/与生产IM对比.md) — **与真实 IM 的对比:核心同构 + 规模工程差距**(同构点 / 全 MQ vs RPC / 存储分片 / 大群扩散 / 有序性取舍 / 功能面差异 + 演进路线图 + 面试讲法)
- [docs/小群设计.md](docs/小群设计.md) — **小群群聊设计思想**(读扩散 vs 写扩散取舍 + 信封 targets 按节点聚合 + 群内保序 + 边界 + 面试讲法;实现细节见 群聊设计)
- [docs/群聊已读.md](docs/群聊已读.md) — **群聊已读:成员水位 + 预聚合计数(按需查询)**(为什么不能像单聊一样推 / 数据模型 / 预聚合机制 / Lua 原子 / 存储决策 / 异常面分析 + 改动清单)
- [docs/多端踢人.md](docs/多端踢人.md) — **多端踢人:同端单设备 + 手动踢设备**(Redis Pub/Sub KICK / 删 token 兜底 / 本地判目标 / 踢人四步 / 心跳修复 + 踩坑)
- [docs/信令分通道.md](docs/信令分通道.md) — **信令与消息分通道:队列级隔离**(topic 布局 / 各侧实现 / 配置不一致坑 / 为什么分通道)
- [docs/EventLoop无锁.md](docs/EventLoop无锁.md) — **Netty EventLoop 为什么无锁**(单消费者 + MPSC 队列 + CAS;无锁与背压的区别;下行链路 20 消费线程 → eventLoop().execute() 的并发模型 + 面试问答)
- [docs/鉴权设计.md](docs/鉴权设计.md) — **鉴权设计:一套凭证、两种协议、连接生命周期**(token 体系 / TCP握手 + HTTP鉴权 / 过期策略 / 吊销与踢下线 / 生命周期框架 / 攻击面 / 当前缺口清单)
- [docs/鉴权完整篇章.md](docs/鉴权完整篇章.md) — **鉴权完整篇章:从凭证到连接生命周期**(深度整合——鉴权本质 / 三身份 / 双协议 / 握手深度(含 EventLoop 阻塞 bug)/ HTTP越权防护 / 过期与长连接 / 吊销与踢下线 / 生命周期 / 攻击面全景 / JWT vs 纯token / 缺口演进 / 面试问答)
- [docs/慢客户端处理方案.md](docs/慢客户端处理方案.md) — **慢客户端处理方案:有界 + 降维**(Netty 水位/停读 / per-channel 有界队列 / MQ 位点必须提交 / 不可达→主动断连→增量拉取补偿 / 降维成"暂时离线" + 面试口述)
- [docs/压测报告1.md](docs/压测报告1.md) — 压测报告 v1(首次压测发现的问题;**数据受 console.log/发送速率等假瓶颈污染,已被报告 2 否定,引用以报告 2 为准**)
- [docs/压测报告2.md](docs/压测报告2.md) — 压测报告 v2(修复后:消息 100% 落库 0 丢失,30 连接 P50 79-108ms)
- [docs/压测报告3.md](docs/压测报告3.md) — 压测报告 v3(云服务器 8C16G Linux 双实例:健康 600 条/秒 P50<100ms,极限 800 条/秒 0 丢消息;公网带宽是压测天花板)
- [docs/压测报告4.md](docs/压测报告4.md) — 压测报告 v4(六指标系统性压测:端到端 P99/3 万空连接/路由基准/送达不重/TPS 拐点 800/s/群扇出 63ms;数据层:慢查询 0、dedup 92 万 key;方法学:慢日志 FILE、预热轮、重跑验证)

## 提交规矩

每次提交代码前,必须更新 `docs/` 与 `README.md` 记录项目进展(见 CLAUDE.md)。
