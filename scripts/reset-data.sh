#!/usr/bin/env bash
# QuantumLink IM - 压测数据清理(MySQL + Redis 配套清 + 可选 RocketMQ 位点重置)
#
# 为什么"配套清"(CLAUDE.md 教训):
#   - MySQL 清 + Redis 计数器不清 → 新消息 seq 从旧值继续,已读水位错位
#   - Redis 清 + MySQL 不清 → seq 回绕撞已读水位
#   本脚本永远把两者一起清,只留在线 token/用户缓存(TTL 自愈,不打扰 jds/jdx)。
#
# 用法:
#   scripts/reset-data.sh                  # 清消息链路(MySQL 消息/群/已读/会话表 + Redis seq/已读/在线 key)
#   scripts/reset-data.sh --users          # 同时删压测用户(lt% 前缀:Node 客户端 lt_r%,Netty 客户端 lt{idx}_{ts})
#   scripts/reset-data.sh --users mypref   # 删指定前缀用户
#   scripts/reset-data.sh --reset-mq       # 重置消费位点到最新 + 重启 chat(见下方"为什么还要重启 chat")
#   scripts/reset-data.sh -y               # 跳过确认
#
# 为什么 --reset-mq 还要重启 chat:
#   mqadmin resetOffsetByTime 只改 broker 端消费位点,chat 消费者本地已拉取的
#   积压缓冲不受影响——位点重置后这些旧消息仍会被消费,把 TRUNCATE 后的表写回
#   (实测写回近 1 万条)。重启 chat = 本地缓冲清零,按新位点重新拉取,积压被跳过。
#   顺序:reset 位点 → kill chat → 重启,端口就绪后脚本返回。
#
# 环境变量(不设用默认,对齐 start-all.sh):
#   IM_MYSQL_HOST/PORT/DB/USER/PASSWORD   IM_REDIS_HOST/PORT/PASSWORD
#   IM_ROCKETMQ_NAMESRV   ROCKETMQ_HOME(找 mqadmin,默认 /opt/rocketmq-all-5.3.1-bin-release)
#   IM_CHAT_PORTS(重启 chat 的端口列表,默认 "8081 8082",对齐 start-all.sh)
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)

MYSQL_ARGS="-h${IM_MYSQL_HOST:-127.0.0.1} -P${IM_MYSQL_PORT:-3306} -u${IM_MYSQL_USER:-root} -p${IM_MYSQL_PASSWORD:-123456} ${IM_MYSQL_DB:-quantumlink}"
REDIS_PASS="${IM_REDIS_PASSWORD:-}"
redis_cli() { redis-cli -h "${IM_REDIS_HOST:-127.0.0.1}" -p "${IM_REDIS_PORT:-6379}" ${REDIS_PASS:+-a "$REDIS_PASS"} --no-auth-warning "$@"; }

DO_USERS=0
# 默认 lt%:覆盖两种压测客户端命名——Node 客户端 lt_r{ts}_p{pid}_u{i}(loadtest-clean.js)、
# Netty 客户端 lt{idx}_{ts}(LoadTestClient)。lt 前缀 = 压测专用,正式用户不用。
USER_PREFIX="lt%"
DO_RESET_MQ=0
YES=0
for a in "$@"; do
  case "$a" in
    --users)   DO_USERS=1; USER_PREFIX="lt%";;
    --users=*) DO_USERS=1; USER_PREFIX="${a#--users=}";;
    --reset-mq) DO_RESET_MQ=1;;
    -y) YES=1;;
    *) echo "未知参数: $a"; echo "用法: reset-data.sh [--users[=前缀]] [--reset-mq] [-y]"; exit 1;;
  esac
done

# ---- 1. 打印将执行的操作 + 确认 ----
echo "==================== QuantumLink 压测数据清理 ===================="
echo "[MySQL] TRUNCATE: im_message / im_group_message / im_group / im_group_member / im_read_pos / im_conversation"
if [ "$DO_USERS" = 1 ]; then echo "[MySQL] 删压测用户: username LIKE '${USER_PREFIX}'(含关联 im_device/im_group_member)"; fi
echo "[Redis] 删 key 模式: im:conv:seq:* im:group_seq:* im:read:* im:group_read:* im:group_msg_read:* im:f2f:* im:session:* im:devices:* im:node:conns:*"
echo "[Redis] 保留: im:token:*(在线登录态,TTL 自愈) im:user:*(用户缓存,TTL 10min 自愈)"
if [ "$DO_RESET_MQ" = 1 ]; then echo "[MQ] 重置消费位点到最新: im-chat-consumer(client2server) / im-chat-deliver-consumer / im-chat-read-consumer(client2signal)"; echo "[MQ] 重启 chat: 清空消费者本地积压缓冲(仅重置位点,旧消息仍会被写回)"; fi
if [ "$YES" != 1 ]; then
  read -r -p "确认执行? [y/N] " ans
  [ "$ans" = "y" ] || [ "$ans" = "Y" ] || { echo "已取消"; exit 0; }
fi

# ---- 2. (可选)重置 MQ 位点 + 重启 chat ----
# resetOffsetByTime 只改 broker 端位点,chat 本地已拉取的积压缓冲还会消费写回
# (实测 TRUNCATE 后写回近 1 万条)——必须重启 chat 清空缓冲,按新位点重新拉取。
if [ "$DO_RESET_MQ" = 1 ]; then
  ROCKETMQ_HOME="${ROCKETMQ_HOME:-/opt/rocketmq-all-5.3.1-bin-release}"
  MQADMIN="$ROCKETMQ_HOME/bin/mqadmin"
  NAMESRV="${IM_ROCKETMQ_NAMESRV:-127.0.0.1:9876}"
  CHAT_PORTS="${IM_CHAT_PORTS:-8081 8082}"
  CHAT_JAR="$ROOT/im-chat/target/im-chat-1.0.0-SNAPSHOT.jar"
  [ -f "$MQADMIN" ] || { echo "[!!] 找不到 mqadmin: $MQADMIN(设 ROCKETMQ_HOME)"; exit 1; }
  [ -f "$CHAT_JAR" ] || { echo "[!!] 找不到 chat jar: $CHAT_JAR"; exit 1; }
  TS=$(date +%s%3N)
  echo "--- 重置消费位点(namesrv=$NAMESRV) ---"
  "$MQADMIN" resetOffsetByTime -n "$NAMESRV" -g im-chat-consumer        -t client2server -s "$TS" 2>&1 | tail -2
  "$MQADMIN" resetOffsetByTime -n "$NAMESRV" -g im-chat-deliver-consumer -t client2signal -s "$TS" 2>&1 | tail -2
  "$MQADMIN" resetOffsetByTime -n "$NAMESRV" -g im-chat-read-consumer    -t client2signal -s "$TS" 2>&1 | tail -2

  echo "--- 重启 chat(清空消费者本地积压缓冲)$CHAT_PORTS ---"
  for p in $CHAT_PORTS; do
    pids=$(ps -eo pid,args | grep "[i]m-chat-.*\.jar.*--server.port=$p" | awk '{print $1}')
    [ -n "$pids" ] && kill $pids && echo "  [stop] im-chat :$p (pid=$pids)"
  done
  sleep 2
  for p in $CHAT_PORTS; do
    echo "  [start] im-chat :$p"
    nohup "$(command -v java)" -Xmx1g -jar "$CHAT_JAR" --server.port="$p" >> "$ROOT/logs/im-chat-$p.log" 2>&1 &
  done
  # 等端口就绪(Spring Boot 启动 ~15-25s;RocketMQ 消费 rebalance 另需几秒)
  for p in $CHAT_PORTS; do
    for i in $(seq 1 30); do
      if (echo > "/dev/tcp/127.0.0.1/$p") 2>/dev/null; then echo "  [ready] im-chat :$p"; break; fi
      sleep 1
    done
  done
  echo "  (chat 已重启,消费者按新位点拉取;若端口未就绪请查 logs/im-chat-*.log)"
fi

# ---- 3. MySQL:TRUNCATE 消息链路表 ----
echo "--- TRUNCATE MySQL ---"
mysql $MYSQL_ARGS -e "TRUNCATE TABLE im_message; TRUNCATE TABLE im_group_message; TRUNCATE TABLE im_group; TRUNCATE TABLE im_group_member; TRUNCATE TABLE im_read_pos; TRUNCATE TABLE im_conversation;" || { echo "[!!] MySQL 清理失败"; exit 1; }
# chat 双实例可能还在消费剩余积压,3s 后再清一次兜底(配合 --reset-mq 通常不会发生)
sleep 3
mysql $MYSQL_ARGS -e "TRUNCATE TABLE im_message; TRUNCATE TABLE im_group_message;" && echo "  (3s 后复查清空完成)"

# ---- 4. Redis:删消息链路状态 key(与 TRUNCATE 配套) ----
echo "--- 清 Redis 消息链路 key ---"
DELETED=$(redis_cli EVAL 'local t=0 for i,p in ipairs(ARGV) do local ks=redis.call("keys",p) for _,k in ipairs(ks) do redis.call("del",k) t=t+1 end end return t' 0 \
  'im:conv:seq:*' 'im:group_seq:*' 'im:read:*' 'im:group_read:*' 'im:group_msg_read:*' 'im:f2f:*' 'im:session:*' 'im:devices:*' 'im:node:conns:*')
echo "  删除 Redis key: $DELETED 个"

# ---- 5. (可选)删压测用户 ----
if [ "$DO_USERS" = 1 ]; then
  echo "--- 删压测用户(username LIKE '${USER_PREFIX}') ---"
  UIDS=$(mysql $MYSQL_ARGS -N -e "SELECT user_id FROM im_user WHERE username LIKE '${USER_PREFIX}'")
  CNT=$(echo "$UIDS" | grep -c . || true)
  if [ "$CNT" -gt 0 ]; then
    # 用子查询删除,不拼 IN 列表——54 万用户拼 IN 会超 shell 参数上限(Argument list too long,实测 2026-08-14)
    mysql $MYSQL_ARGS -e "DELETE FROM im_device WHERE user_id IN (SELECT user_id FROM im_user WHERE username LIKE '${USER_PREFIX}'); DELETE FROM im_group_member WHERE user_id IN (SELECT user_id FROM im_user WHERE username LIKE '${USER_PREFIX}'); DELETE FROM im_user WHERE username LIKE '${USER_PREFIX}';"
    echo "  删除用户: $CNT 个"
  else
    echo "  无匹配用户,跳过"
  fi
fi

echo "==================== 清理完成 ===================="
echo "提示: 在线 token(im:token:*)未被清,已登录客户端不受影响;清库后消息 seq 从 1 重新发号。"
