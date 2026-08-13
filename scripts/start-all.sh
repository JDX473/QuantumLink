#!/usr/bin/env bash
# QuantumLink IM - Linux one-click start
# 启动 im-chat × N + im-connect × N(中间件 MySQL/Redis/RocketMQ/Nacos/MinIO 假设已装好并运行,
# 只做端口就绪检查,不负责安装)。
#
# 环境变量覆盖(不设则用默认值,默认适配 8C16G 单机):
#   IM_ROOT            项目根目录(默认=本脚本上级目录)
#   JAVA_HOME          JDK 17 路径(默认用 PATH 里的 java)
#   IM_CHAT_PORTS      chat 端口列表,空格分隔(默认 "8081 8082" 双实例,分摊 CPU)
#   IM_CONNECT_PORTS   connect 端口列表,空格分隔(默认 "19001 19002")
#   IM_CONNECT_HOST    connect 对外地址(节点注册 Nacos / 调度返回给客户端;云上=公网 IP,默认 127.0.0.1)
#   IM_MYSQL_HOST/PORT/DB/USER/PASSWORD   chat 用(application.yml 消费)
#   IM_REDIS_HOST/PORT/PASSWORD           chat + connect 共用
#   IM_ROCKETMQ_NAMESRV                   chat + connect 共用
#   IM_NACOS_ADDR                          chat + connect 共用
#   IM_MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET   chat 用
#
# 用法:
#   scripts/start-all.sh
#   IM_CHAT_PORTS="8081" IM_CONNECT_PORTS="19001" scripts/start-all.sh   # 单 chat 单 connect
#
# 中间件注意:
#   - 中间件需自行安装并启动(本脚本只检查端口就绪)。
#   - RocketMQ broker 会把启动时的网卡 IP 写死进 brokerIP1 注册到 namesrv;
#     若网卡变化(如 VPN 断开)客户端会报 "connect to <旧IP>:10911 failed"。
#     修复:启动 broker 时用 -c 指定 brokerIP1 的配置(本地见
#     F:/Study/RocketMQ/rocketmq-all-5.3.1-bin-release/conf/broker-local.conf,
#     内含 brokerIP1=127.0.0.1;云上多网卡时同样需要)。
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
[ -n "${JAVA:-}" ] || JAVA=java
CHAT_JAR="$ROOT/im-chat/target/im-chat-1.0.0-SNAPSHOT.jar"
CONNECT_JAR="$ROOT/im-connect/target/im-connect-1.0.0-SNAPSHOT.jar"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

# chat 默认双实例:chat 是吞吐瓶颈,8 核单实例浪费算力,双实例经 RocketMQ 同 group 分摊队列
CHAT_PORTS="${IM_CHAT_PORTS:-8081 8082}"
CONNECT_PORTS="${IM_CONNECT_PORTS:-19001 19002}"

# bash 内置 TCP 探活(不需 nc)
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&- 3<&-; return 0; } || return 1; }

# Nacos 检查端口从 IM_NACOS_ADDR(host:port) 取,默认 8850
NACOS_PORT="${IM_NACOS_ADDR##*:}"
[ -n "$NACOS_PORT" ] || NACOS_PORT=8850

echo "==================== QuantumLink start-all ===================="
echo "  ROOT: $ROOT | JAVA: $JAVA | chat=$CHAT_PORTS connect=$CONNECT_PORTS"
[ -f "$CHAT_JAR" ] || { echo "[!!] 找不到 $CHAT_JAR,先构建:mvn package -DskipTests"; exit 1; }
[ -f "$CONNECT_JAR" ] || { echo "[!!] 找不到 $CONNECT_JAR,先构建:mvn package -DskipTests"; exit 1; }

# 中间件端口就绪检查(未就绪仅提示,不阻塞——中间件需自行先启动)
check_middleware() {
  if port_open "$2"; then echo "[ok ] $1 :$2"; else echo "[!! ] $1 :$2 未就绪(请先启动中间件)"; fi
}
check_middleware MySQL            3306
check_middleware Redis            6379
check_middleware RocketMQ-namesrv 9876
check_middleware RocketMQ-broker  10911
check_middleware Nacos            "$NACOS_PORT"
check_middleware MinIO            9000

# ---- im-chat × N ----
for p in $CHAT_PORTS; do
  if port_open "$p"; then
    echo "[skip] im-chat :$p 已在运行"
  else
    echo "[start] im-chat :$p"
    # IM_* 环境变量直接继承给 chat(application.yml ${IM_*:default} 消费);
    # --server.port 覆盖 yml 端口(命令行参数优先级最高),支持多实例
    nohup "$JAVA" -Xmx1g -jar "$CHAT_JAR" --server.port="$p" >> "$LOGS/im-chat-$p.log" 2>&1 &
  fi
done

# ---- im-connect × N ----
for p in $CONNECT_PORTS; do
  if port_open "$p"; then
    echo "[skip] im-connect :$p 已在运行"
  else
    echo "[start] im-connect :$p"
    nohup "$JAVA" -Xmx512m \
      -Dim.connect.port="$p" \
      -Dim.connect.host="${IM_CONNECT_HOST:-127.0.0.1}" \
      -Dim.connect.redis.host="${IM_REDIS_HOST:-127.0.0.1}" \
      -Dim.connect.redis.port="${IM_REDIS_PORT:-6379}" \
      -Dim.connect.redis.password="${IM_REDIS_PASSWORD:-}" \
      -Dim.connect.namesrv="${IM_ROCKETMQ_NAMESRV:-127.0.0.1:9876}" \
      -Dim.connect.nacos="${IM_NACOS_ADDR:-127.0.0.1:8850}" \
      -jar "$CONNECT_JAR" >> "$LOGS/im-connect-$p.log" 2>&1 &
  fi
done

echo "--------------------------------------------------------------"
echo "等待启动..."; sleep 8
for p in $CHAT_PORTS $CONNECT_PORTS; do
  port_open "$p" && echo "[up ] :$p" || echo "[-- ] :$p 未起来(查 $LOGS 日志)"
done
echo "==================== Done ===================="
echo "  chat    : curl http://127.0.0.1:${CHAT_PORTS%% *}/api/connects"
echo "  logs    : $LOGS"
