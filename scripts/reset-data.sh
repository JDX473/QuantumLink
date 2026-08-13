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
#   scripts/reset-data.sh --users          # 同时删压测用户(lt_r% 前缀,loadtest-clean.js 生成)
#   scripts/reset-data.sh --users mypref   # 删指定前缀用户
#   scripts/reset-data.sh --reset-mq       # 重置 RocketMQ 消费位点到最新(丢弃积压,防 TRUNCATE 后被积压消息写回)
#   scripts/reset-data.sh -y               # 跳过确认
#
# 环境变量(不设用默认,对齐 start-all.sh):
#   IM_MYSQL_HOST/PORT/DB/USER/PASSWORD   IM_REDIS_HOST/PORT/PASSWORD
#   IM_ROCKETMQ_NAMESRV   ROCKETMQ_HOME(找 mqadmin,默认 /opt/rocketmq-all-5.3.1-bin-release)
set -u

MYSQL_ARGS="-h${IM_MYSQL_HOST:-127.0.0.1} -P${IM_MYSQL_PORT:-3306} -u${IM_MYSQL_USER:-root} -p${IM_MYSQL_PASSWORD:-123456} ${IM_MYSQL_DB:-quantumlink}"
REDIS_PASS="${IM_REDIS_PASSWORD:-}"
redis_cli() { redis-cli -h "${IM_REDIS_HOST:-127.0.0.1}" -p "${IM_REDIS_PORT:-6379}" ${REDIS_PASS:+-a "$REDIS_PASS"} --no-auth-warning "$@"; }

DO_USERS=0
USER_PREFIX="lt_r%"
DO_RESET_MQ=0
YES=0
for a in "$@"; do
  case "$a" in
    --users)   DO_USERS=1; USER_PREFIX="lt_r%";;
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
if [ "$DO_RESET_MQ" = 1 ]; then echo "[MQ] 重置消费位点到最新: im-chat-consumer(client2server) / im-chat-deliver-consumer / im-chat-read-consumer(client2signal)"; fi
if [ "$YES" != 1 ]; then
  read -r -p "确认执行? [y/N] " ans
  [ "$ans" = "y" ] || [ "$ans" = "Y" ] || { echo "已取消"; exit 0; }
fi

# ---- 2. (可选)重置 MQ 位点:必须先于 TRUNCATE——否则 chat 还在消费的积压消息会立即把 TRUNCATE 后的表写回 ----
if [ "$DO_RESET_MQ" = 1 ]; then
  ROCKETMQ_HOME="${ROCKETMQ_HOME:-/opt/rocketmq-all-5.3.1-bin-release}"
  MQADMIN="$ROCKETMQ_HOME/bin/mqadmin"
  NAMESRV="${IM_ROCKETMQ_NAMESRV:-127.0.0.1:9876}"
  [ -f "$MQADMIN" ] || { echo "[!!] 找不到 mqadmin: $MQADMIN(设 ROCKETMQ_HOME)"; exit 1; }
  TS=$(date +%s%3N)
  echo "--- 重置消费位点(namesrv=$NAMESRV) ---"
  "$MQADMIN" resetOffsetByTime -n "$NAMESRV" -g im-chat-consumer        -t client2server -s "$TS" 2>&1 | tail -2
  "$MQADMIN" resetOffsetByTime -n "$NAMESRV" -g im-chat-deliver-consumer -t client2signal -s "$TS" 2>&1 | tail -2
  "$MQADMIN" resetOffsetByTime -n "$NAMESRV" -g im-chat-read-consumer    -t client2signal -s "$TS" 2>&1 | tail -2
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
    IN=$(echo "$UIDS" | sed "s/^/'/;s/$/'/" | paste -sd, -)
    mysql $MYSQL_ARGS -e "DELETE FROM im_user WHERE username LIKE '${USER_PREFIX}'; DELETE FROM im_device WHERE user_id IN ($IN); DELETE FROM im_group_member WHERE user_id IN ($IN);"
    echo "  删除用户: $CNT 个"
  else
    echo "  无匹配用户,跳过"
  fi
fi

echo "==================== 清理完成 ===================="
echo "提示: 在线 token(im:token:*)未被清,已登录客户端不受影响;清库后消息 seq 从 1 重新发号。"
