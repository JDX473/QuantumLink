# QuantumLink IM

高并发 IM 即时通讯系统(Java 后端秋招主项目)。从 0 编写,目标是每个模块能讲清机制、有取舍、可压测。

## 架构

```
客户端(自定义 TCP)
   │  HANDSHAKE / MSG / PING / DELIVER_ACK
   ▼
im-connect(port 9999,Netty 长连接层)
   │  握手鉴权 · 心跳 · EventLoop 异步化 · per-conversation 保序 · 会话注册
   │  注册 Nacos · 上报连接数(最少连接调度的数据源)
   │
   │  RocketMQ(上行 client2server / deliver_ack)
   ▼
im-chat(port 8081,Spring Boot 业务层)
   │  注册登录 · 幂等 · 落库(Redis INCR 取 seq) · 离线拉取 · 双 ACK 回执
   │  RocketMQ(下行 server2client)
   ▼
im-connect → 目标客户端
```

**两层解耦**:`im-connect`(长连接)与 `im-chat`(业务)**零代码依赖**,只经 RocketMQ + Redis 通信,可独立部署/扩容。

## 模块

| 模块 | 端口 | 职责 | 状态 |
|------|------|------|------|
| im-common | — | 自定义 TCP 协议帧、DTO、工具 | ✅ 协议编解码+测试通过 |
| im-connect | 9999 | Netty 长连接层:握手鉴权/心跳/保序/上行下行/注册Nacos+上报连接数 | ✅ 端到端打通 |
| im-gateway | 88 | 入口代理(负载均衡+Nacos 路由) | MVP 后置 |
| im-chat | 8081 | 业务层:注册登录/幂等/落库/seq/离线拉取/双ACK/最少连接调度/群聊 | ✅ 端到端打通 |
| im-loadtest | — | 压测客户端 | MVP 后置 |
| im-desktop(客户端) | — | Electron 桌面端(TCP 走主进程,UI 走渲染进程) | ✅ 窗口+登录+收发 |

## 技术栈

Java 17 · Maven · Netty 4.1 · RocketMQ 5 · Redis 7 · MySQL 8 · Spring Boot 3 · MyBatis-Plus · Lettuce

## 快速开始

```bash
# 1. 启动本机中间件(RocketMQ + Redis + Nacos;MySQL 假设已作为 Windows 服务运行)
scripts/start-middleware.cmd

# 2. 构建(本机默认 JDK8,需用 JDK17)
JAVA_HOME="D:\\jdk17" mvn clean package -DskipTests

# 3. 启动业务层
java -jar im-chat/target/im-chat-1.0.0-SNAPSHOT.jar

# 4. 启动长连接层
java -jar im-connect/target/im-connect-1.0.0-SNAPSHOT.jar

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
| client_msg_id 客户端生成 | 幂等键必须客户端生成,重发才能带同一个;消息身份 server_msg_id 由服务端生成 |
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
- **2026-08-03(有序性技术文章)**:沉淀 [docs/ordering-article.md](docs/ordering-article.md)——完整记录消息有序性从踩坑(DB锁/MQ串行/抢锁/异步send/EventLoop阻塞)到解决(Redis INCR + 四跳保序)的思考过程,含 10 个面试问答。
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
- **2026-08-05(压测驱动修复:executor 泄漏 / keys 扫描 / 用户资料缓存)**:压测(loadtest)驱动三个设计缺陷修复。① **per-会话 executor 线程泄漏**:每会话一个单线程 executor 永不回收,200 连接 439 线程(压测实锤)→ 改**共享有界线程池 48 槽 + hash 路由**(同一会话 hash 到同一线程保序不变,线程固定不增长)。② **Redis keys 扫描**:`keys("im:session:{uid}:*")` O(N) 全库扫描阻塞单线程 Redis(压测曾 1 分钟超时)→ 改**设备 Set `im:devices:{uid}` + SMEMBERS**。③ **fillSenderProfile 每条查 DB**:chat 每条消息查 User 表(压测 chat CPU 57%)→ 改 **Redis 用户资料缓存 `im:user:{uid}`**(改头像失效)。④ 客户端压测加 quiet 模式(console.log 阻塞)。**压测结论**:链路可靠(消息 100% 落库 0 丢失),30 连接 P50 79-108ms(缓存后 2 倍改善);连接数上限需云服务器(Windows 端口限制)。报告见 docs/loadtest-report-2.md。
- **2026-08-05(chat 多实例 + 雪花主键 + JS 精度修复)**:① **chat 多实例验证通过**:RocketMQ 同 group 自动分摊(8081+8082 双实例都消费);Redis INCR 发号 + 队列 hash 路由保证**实例稳定后同会话 seq 严格有序**;幂等双保险扛并发。**已知边界**:实例增减瞬间(rebalance)可能瞬时乱序(队列消费权切换),生产可接受(客户端按 seq 排序 + 增量拉取自愈)。② **雪花主键**:im_message/im_group_message 主键从 DB 自增改 **MyBatis-Plus ASSIGN_ID(雪花)**,多 chat 实例全局唯一(之前 DB 自增会撞);schema/表去掉 AUTO_INCREMENT。③ **JS 精度修复**:19 位雪花 id 超过 JS Number 安全范围(2^53)导致客户端丢精度 → **serverMsgId 下发改为 String**(字段类型 Long→String,`String.valueOf(id)`),客户端统一字符串处理。**验证**:双实例下跨节点互聊通过、雪花 id DB 无重复、seq 稳定后严格有序。
- **2026-08-05(面对面建群)**:微信式"输 4 位数字快速建群"。**核心机制**:Redis `im:f2f:{code}` 映射 code → 群,**5 分钟 TTL 时间窗口**(到期自动释放数字,无需定时任务);**Lua 原子"查/建"**(Redis 单线程,并发输入同数字只建一个群——先到者 CREATE,后到者 EXIST 复用);群名自动"面对面建群 {code}",200 人上限,加人幂等。**踩坑**:Spring `DefaultRedisScript` 返回类型必须 `List.class`——`Object.class` 会把 Lua multi-bulk 误解析成单值导致 action 判断失效。**验证**:A 建群 → B/C 并发加入同群 → 跨节点实时收群消息 → 重复输入幂等。
- **2026-08-05(单元测试 + 快速启动脚本)**:① **单测 116 个全过**(im-common 28 / im-connect 15 / im-chat 73),JaCoCo 绑定三个模块;业务逻辑类**指令覆盖率 83%**(核心服务 AuthService 97%、MessageQueryService 93%、MessageService 84%、UserCacheService 90%、GroupController 79%);纯 DTO/基础设施类(Netty 服务器/RocketMQ 生产消费者/MinIO)不做单测(测了是测框架 mock,无价值)。② **快速启动脚本**:`scripts/start-all.cmd`(服务端一键启动:检测端口缺什么起什么)+ `scripts/start-client.cmd`(客户端一键启动:检测服务端→装依赖→起 Electron)。**踩坑**:cmd 脚本必须 GBK/纯 ASCII 编码(UTF-8 被 cmd 按 GBK 误读成乱码命令)、Electron 启动必须带 app 路径 `.`(否则显示 "To run a local app" 提示)、`findstr` 正则简化、`start` 标题必须带引号。
- **2026-08-06(单聊已读:可靠投递第三态)**:新增已读回执——B 打开会话/看到新消息时上报"我已读到 seq X",服务端单调推进水位(Redis Lua 原子 + MySQL `im_read_pos` 持久化)并推 READ 事件给 A,A 渲染"对方已读"。**核心设计**:① 已读是**派生状态**——发送方用"对端水位"推导(自己消息.seq ≤ 对端水位 = 已读),**不写共享的 im_message 行**(A#B 共享,逐条标已读分不清方向);② 读水位 O(1) 表达(seq 会话内单调,"读到 seq X" = "≤X 全已读");③ **离线不丢**——拉历史接口带 `peerReadSeq`,实时事件管当下、拉取接口管历史;④ 协议加 `READ_ACK` 帧(9)+ `DownstreamEnvelope.TYPE_READ`,上行走 `read_report` topic,复用 DELIVER_ACK 通道。**验证**:A 发 → B 收 → B 上报 → A 秒收 READ 事件(reader=B untilSeq=1),Redis 水位=1、MySQL read_seq=1、拉历史 peerReadSeq=1 全对(`scripts/verify-read.js`)。
- **2026-08-06(已读修复:状态竞态 + 增量打开 + 状态显示)**:实测发现并修复三个问题。① **DELIVER 晚于 READ 把已读降级**:DELIVER(对方已送达)与 READ(对方已读)走两个 topic/两个消费者,到达顺序不保证——连发消息时 DELIVER 偶发晚于 READ,旧代码无条件置"已送达"把"已读"打回(症状:最后一条变已送达)。修复为客户端消息状态**只进不退**(sending<stored<delivered<read,`updateMessageStatus` 按 rank 判定)+ `onAck` 用对端水位补判;确认 **ACK 恒先于 READ**(消息先存储才能被读,"READ 先于 ACK"仅兜底保险)。② **重进会话消息丢失**:`openConversation` 原单次 `afterSeq=0&limit=50`,会话 >50 条时最新消息被截断——改为**增量打开**:会话内缓存(实时消息按 serverMsgId 去重)+ 首次打开只拉最近 50 条(对齐微信,历史靠向上翻)+ 重开只拉游标之后增量,与 `afterSeq` 增量同步架构一致。③ **UI 只标自己消息的状态**:对方发的消息不显示状态标签(只显示时间),对齐微信/Discord。

## 分支

- `master`:稳定版,已验证的功能
- `dev`:新功能开发分支(当前)

## 文档

- [docs/mvp-design.md](docs/mvp-design.md) — MVP 设计与实现方案(权威)
- [docs/ordering-article.md](docs/ordering-article.md) — **IM 消息有序性:从踩坑到解决**(深度技术文章,含 5 个坑 + 保序架构 + **分布式保序** + 11 个面试问答)
- [docs/downstream-delivery-article.md](docs/downstream-delivery-article.md) — **IM 下行投递:从"时好时坏"到"一个 group 一个节点"**(RocketMQ Topic/Group/Tag 三层机制 + consumer group 共享的坑 + 修复设计 + 11 个面试问答)
- [docs/group-chat-design.md](docs/group-chat-design.md) — **群聊设计:读扩散 + 按节点聚合推送**(扩散模型选型 + 信封 targets 聚合 + 群消息链路 + 踩坑)

## 提交规矩

每次提交代码前,必须更新 `docs/` 与 `README.md` 记录项目进展(见 CLAUDE.md)。
