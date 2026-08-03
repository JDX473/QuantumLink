# QuantumLink IM

高并发 IM 即时通讯系统(Java 后端秋招主项目)。从 0 编写,目标是每个模块能讲清机制、有取舍、可压测。

## 架构

```
客户端(自定义 TCP)
   │  HANDSHAKE / MSG / PING
   ▼
im-connect(port 9999,Netty 长连接层)
   │  握手鉴权 · 心跳 · EventLoop 异步化 · 会话注册
   │
   │  RocketMQ(上行 im_upstream)
   ▼
im-chat(port 8081,Spring Boot 业务层)
   │  鉴权 · 幂等 · 落库(分配 seq) · 离线 · 回执
   │  RocketMQ(下行 im_downstream)
   ▼
im-connect → 目标客户端
```

**两层解耦**:`im-connect`(长连接)与 `im-chat`(业务)**零代码依赖**,只经 RocketMQ + Redis 通信,可独立部署/扩容。

## 模块

| 模块 | 端口 | 职责 | 状态 |
|------|------|------|------|
| im-common | — | 自定义 TCP 协议帧、DTO、工具 | ✅ 协议编解码+测试通过 |
| im-connect | 9999 | Netty 长连接层:握手鉴权/心跳/上行 | ✅ 端到端打通 |
| im-gateway | 88 | 入口代理(负载均衡+Nacos 路由) | MVP 后置 |
| im-chat | 8081 | 业务层:鉴权/持久化/seq/离线/回执 | ✅ 端到端打通 |
| im-loadtest | — | 压测客户端 | MVP 后置 |

## 技术栈

Java 17 · Maven · Netty 4.1 · RocketMQ 5 · Redis 7 · MySQL 8 · Spring Boot 3 · MyBatis-Plus · Lettuce

## 快速开始

```bash
# 1. 启动本机中间件(RocketMQ + Redis;MySQL 假设已作为 Windows 服务运行)
scripts/start-middleware.cmd

# 2. 构建(本机默认 JDK8,需用 JDK17)
JAVA_HOME="D:\\jdk17" mvn clean package -DskipTests

# 3. 启动业务层
java -jar im-chat/target/im-chat-1.0.0-SNAPSHOT.jar

# 4. 启动长连接层
java -jar im-connect/target/im-connect-1.0.0-SNAPSHOT.jar
```

## 关键设计决策

| 决策 | 理由 |
|------|------|
| 自定义 TCP 协议 | 粘包拆包/握手/心跳全是可深挖的真实考点;比 WebSocket 硬核 |
| msgId 客户端生成 | 幂等键必须客户端生成,重发才能带同一个;消息身份 server_msg_id 由服务端生成 |
| seq 服务端 DB 自增 | 排序号必须与落库同事务,不用 Redis INCR(会重复) |
| 双 ACK(STORE/DELIVER) | 区分"已存储"与"已送达",覆盖不同故障边界 |
| 先落库后缓存 | 可靠锚点在 MySQL,Redis 可丢弃,客户端 seq 补拉自愈 |
| EventLoop 只收发 | 阻塞调用丢业务线程池,避免线程雪崩 |

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

## 文档

- [docs/mvp-design.md](docs/mvp-design.md) — MVP 设计与实现方案(权威)
- [docs/ordering-article.md](docs/ordering-article.md) — **IM 消息有序性:从踩坑到解决**(深度技术文章,含 5 个坑 + 保序架构 + 面试问答)

## 提交规矩

每次提交代码前,必须更新 `docs/` 与 `README.md` 记录项目进展(见 CLAUDE.md)。
