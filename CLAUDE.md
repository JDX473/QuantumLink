# CLAUDE.md — QuantumLink IM 项目记忆

## 项目背景

QuantumLink:27 届秋招主项目,Java 后端深度 IM。从 0 写,目标是撑住面试深挖——每个模块能讲清机制、有取舍、可压测。

- 用户:江东旭(27 届,北邮硕士,方向 Java 后端 + AI Agent)
- 技术栈:Java 17、Maven、Netty、RocketMQ、Nacos、Redis、MySQL、Lettuce、MyBatis-Plus
- 远程仓库:`git@github.com:JDX473/QuantumLink.git`
- 当前阶段:设计共识(MVP 方案已定,待进入 Phase 0 脚手架)

## 硬性提交规矩(必须遵守)

**每次提交代码前,必须先更新文档和 README,记录本次项目进展。** 具体:

1. **push 前必做**:更新 `docs/` 下对应模块的文档 + 更新 `README.md`(架构/模块/进展),再提交。不允许"代码推上去了、文档之后补"。
2. **commit message 自解释**:写清"为什么/做了什么",不写空泛的 "update"。
3. **文档随代码同一 commit**:两者进同一个 commit,保证历史可追溯。
4. 每完成一个阶段/里程碑,在 README 的"项目进展"部分追加记录。

## 设计决策记录(实现时遵守)

- **协议**:自定义 TCP(定长头+变长体+crc32),非 WebSocket。帧头 `magic(4B)+version(1B)+type(1B)+bodyLen(4B)`+crc32(4B)帧尾,帧头无 seq(消息身份在 body)。粘包拆包用 `LengthFieldBasedFrameDecoder(lengthFieldOffset=6, lengthFieldLength=4, lengthAdjustment=4)`。HANDSHAKE/HANDSHAKE_ACK 握手鉴权;PING/PONG 心跳。
- **身份体系(服务端分配为主)**:user_id(注册分配)、device_id(首次登录分配,区分客户端/多端基础)、server_msg_id(落库生成,消息正式身份)。幂等键 client_msg_id 客户端生成(`device_id+自增`),去重键 = sender_id+client_msg_id。下行带 server_msg_id+seq,不带 client_msg_id。
- **有序 seq**:服务端落库同一事务内 `UPDATE im_conversation SET last_seq=last_seq+1` 分配;不用 Redis INCR。
- **可靠投递**:双 ACK(STORE=已落库 / DELIVER=对方已送达),发送方超时同一 client_msg_id 重传(3s 超时、指数退避、上限 6 次)。
- **缓存**:先 MySQL 后 Redis,消息 append-only 不双删,客户端 seq 补拉自愈。
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

# 启动本机中间件(RocketMQ + Redis,MySQL 假设已作为服务运行)
scripts/start-middleware.cmd

# 测试
mvn test
```

## 本机中间件(不用 Docker,用户本地已装)

- MySQL 8: `127.0.0.1:3306`, root/123456, 库 `quantumlink`(独立库,与旧项目 `im` 库隔离)
- Redis: `F:\Study\Redis4`(redis-server.exe, 127.0.0.1:6379, 无密码)
- RocketMQ 5.3.1: `F:\Study\RocketMQ\rocketmq-all-5.3.1-bin-release`(namesrv 9876 / broker 10911, 本机直接跑 .cmd)
- 构建需 JDK 17(`D:\jdk17`), 本机默认 JAVA_HOME 是 JDK8

## 权威规格

- MVP 完整设计:见 `docs/mvp-design.md`(帧格式、链路、幂等、有序、离线、存储、边界场景、决策记录)。

## 文档结构

- `docs/mvp-design.md` — MVP 设计与实现方案(权威)
- `docs/` 下后续按模块补充:协议、可靠投递、有序、离线、存储、压测
- `README.md` — 架构总览 + 模块 + 启动步骤 + 设计决策 + 项目进展
