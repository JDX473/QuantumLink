#!/usr/bin/env bash
# QuantumLink 压测观测器:CPU / 进程CPU / Redis / MQ 消费速率 / 节点连接数 / 落库速率
#
# 用法: scripts/observe-lt.sh <tag> <durationSec>     # 后台跑: nohup scripts/observe-lt.sh e1 120 >/dev/null 2>&1 &
# 输出: logs/observe-<tag>.tsv,每 5s 一行,字段:
#   ts cpuUsr cpuSys cpuIdle load1 chat8081 chat8082 conn19001 conn19002
#   redisOps redisBlocked redisClients redisMemMB mqUpRate mqSigRate mqS2c1Rate mqS2c2Rate
#   nodeConn19001 nodeConn19002 insRate dbsize
#   (mq*Rate = 消费速率 条/s,正值=积压增长;nodeConn = im:node:conns 实时值;dbsize 30s 一次)
#
# 环境变量(默认对齐 start-all.sh / reset-data.sh):
#   IM_REDIS_HOST/PORT/PASSWORD  IM_ROCKETMQ_NAMESRV  ROCKETMQ_HOME
#   IM_MYSQL_HOST/PORT/USER/PASSWORD/DB
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG="${1:-default}"
DUR="${2:-300}"
OUT="$ROOT/logs/observe-$TAG.tsv"
mkdir -p "$ROOT/logs"
: > "$OUT"

NCPU=$(nproc)
HZ=$(getconf CLK_TCK)

# ---- 工具 ----
redis() { redis-cli -h "${IM_REDIS_HOST:-127.0.0.1}" -p "${IM_REDIS_PORT:-6379}" \
  ${IM_REDIS_PASSWORD:+-a "$IM_REDIS_PASSWORD" --no-auth-warning} "$@"; }
MYSQL_ARGS=(-h"${IM_MYSQL_HOST:-127.0.0.1}" -P"${IM_MYSQL_PORT:-3306}" -u"${IM_MYSQL_USER:-root}" -p"${IM_MYSQL_PASSWORD:-123456}" "${IM_MYSQL_DB:-quantumlink}")
NAMESRV="${IM_ROCKETMQ_NAMESRV:-127.0.0.1:9876}"
MQADMIN="${ROCKETMQ_HOME:-/opt/rocketmq-all-5.3.1-bin-release}/bin/mqadmin"
[ -x "$MQADMIN" ] || MQADMIN=""

# 进程 pid:按 jar 名 + 端口参数匹配(chat 用 --server.port,connect 用 -Dim.connect.port)
pid_of() {
  ps -eo pid,args | grep "[i]m-$1-.*jar" | grep -- "$2" | awk '{print $1}' | head -1
}

# /proc/<pid>/stat 的 utime+stime(ticks);不存在返回 0
cpu_ticks() {
  [ -r "/proc/$1/stat" ] && awk '{print $14+$15}' "/proc/$1/stat" 2>/dev/null || echo 0
}
# /proc/stat 首行 -> "idle total"(ticks)
cpu_total() {
  awk '/^cpu / {idle=$5+$6; print idle, $2+$3+$4+$5+$6+$7+$8+$9+$10+$11}' /proc/stat
}

# MQ group 消费积压总量(consumerProgress 所有 topic 队列 diff 求和)
mq_diff() {
  [ -n "$MQADMIN" ] || { echo 0; return; }
  "$MQADMIN" consumerProgress -n "$NAMESRV" -g "$1" 2>/dev/null \
    | awk 'NF>=7 && $1 !~ /^#/ && $7 ~ /^[0-9]+$/ {s+=$7} END {print s+0}'
}

innodb_rows() {
  mysql "${MYSQL_ARGS[@]}" -N -e "SHOW GLOBAL STATUS LIKE 'Innodb_rows_inserted'" 2>/dev/null | awk '{print $2}'
}

# ---- 进程组(chat 双实例 + connect 双节点,端口硬编码对齐 start-all.sh 默认) ----
P_CHAT1=$(pid_of chat "--server.port=8081"); P_CHAT2=$(pid_of chat "--server.port=8082")
P_CONN1=$(pid_of connect "-Dim.connect.port=19001"); P_CONN2=$(pid_of connect "-Dim.connect.port=19002")

# 连接数 key:im:node:conns:{nodeId}(nodeId = host:port,含冒号;只取前两个节点)
NODES=$(redis --scan --pattern 'im:node:conns:*' 2>/dev/null | sed 's/^im:node:conns://' | head -2)
NODE1="im:node:conns:$(echo "$NODES" | sed -n '1p')"
NODE2="im:node:conns:$(echo "$NODES" | sed -n '2p')"
# MQ 消费 group:connect 按节点独立 group(nodeId 冒号点转下划线,im-connect-consumer-{nodeId}-msg)
GRP_MSG1="im-connect-consumer-$(echo "${NODE1#im:node:conns:}" | tr ':. ' '___')-msg"
GRP_MSG2="im-connect-consumer-$(echo "${NODE2#im:node:conns:}" | tr ':. ' '___')-msg"

# ---- 初始化 ----
C1=$(cpu_total); PREV_IDLE=${C1%% *}; PREV_TOTAL=${C1##* }
PREV_T1=$(cpu_ticks "$P_CHAT1"); PREV_T2=$(cpu_ticks "$P_CHAT2")
PREV_T3=$(cpu_ticks "$P_CONN1"); PREV_T4=$(cpu_ticks "$P_CONN2")
PREV_UP=$(mq_diff "im-chat-consumer"); PREV_SIG=$(mq_diff "im-chat-deliver-consumer")
PREV_S2C1=$(mq_diff "$GRP_MSG1"); PREV_S2C2=$(mq_diff "$GRP_MSG2")
PREV_ROWS=$(innodb_rows)
PREV_DBSIZE=""; PREV_DBSIZE_T=0
START=$SECONDS

echo -e "ts\tcpuUsr\tcpuSys\tcpuIdle\tload1\tchat8081\tchat8082\tconn19001\tconn19002\tredisOps\tredisBlocked\tredisClients\tredisMemMB\tmqUpRate\tmqSigRate\tmqS2c1Rate\tmqS2c2Rate\tnodeConn19001\tnodeConn19002\tinsRate\tdbsize" > "$OUT"

# ---- 每 5s 采样一轮 ----
while [ $((SECONDS - START)) -lt "$DUR" ]; do
  sleep 5

  # CPU 总体(差值/间隔/核数)
  C2=$(cpu_total); IDLE2=${C2%% *}; TOTAL2=${C2##* }
  DIFF_TOTAL=$((TOTAL2 - PREV_TOTAL)); DIFF_IDLE=$((IDLE2 - PREV_IDLE))
  USR=$((DIFF_TOTAL - DIFF_IDLE))
  if [ "$DIFF_TOTAL" -gt 0 ]; then
    CPU_IDLE=$(awk "BEGIN{printf \"%.1f\", 100*$DIFF_IDLE/$DIFF_TOTAL}")
    CPU_USR=$(awk "BEGIN{printf \"%.1f\", 100*$USR/$DIFF_TOTAL}")
  else CPU_IDLE=0; CPU_USR=0; fi
  CPU_SYS=$(awk "BEGIN{printf \"%.1f\", 100-$CPU_USR-$CPU_IDLE}")
  PREV_IDLE=$IDLE2; PREV_TOTAL=$TOTAL2
  LOAD1=$(cut -d' ' -f1 /proc/loadavg)

  # 进程 CPU%((tick差值)/HZ/间隔/核数×100)
  proc_cpu() { # $1=prev ticks $2=pid -> "new_prev pct"
    local now
    if [ -n "$2" ] && [ -r "/proc/$2/stat" ]; then now=$(cpu_ticks "$2"); else now=0; fi
    if [ -n "$2" ] && [ "$1" -gt 0 ] && [ "$now" -gt 0 ]; then
      echo "$now $(awk "BEGIN{printf \"%.1f\", ($now-$1)/$HZ/5/$NCPU*100}")"
    else
      echo "${now:-0} 0"
    fi
  }
  read -r PREV_T1 CPU_CHAT1 <<< "$(proc_cpu "$PREV_T1" "$P_CHAT1")"
  read -r PREV_T2 CPU_CHAT2 <<< "$(proc_cpu "$PREV_T2" "$P_CHAT2")"
  read -r PREV_T3 CPU_CONN1 <<< "$(proc_cpu "$PREV_T3" "$P_CONN1")"
  read -r PREV_T4 CPU_CONN2 <<< "$(proc_cpu "$PREV_T4" "$P_CONN2")"

  # Redis(instantaneous_ops_per_sec 直接读,不用差值)
  REDIS_INFO=$(redis info stats clients memory 2>/dev/null)
  OPS=$(echo "$REDIS_INFO" | awk -F: '/^instantaneous_ops_per_sec:/{gsub("\r","");print $2}')
  BLOCKED=$(echo "$REDIS_INFO" | awk -F: '/^blocked_clients:/{gsub("\r","");print $2}')
  CLIENTS=$(echo "$REDIS_INFO" | awk -F: '/^connected_clients:/{gsub("\r","");print $2}')
  MEM=$(echo "$REDIS_INFO" | awk -F: '/^used_memory:/{gsub("\r","");print int($2/1048576)}')

  # MQ 消费速率(积压 diff 差值/间隔,正值=增长)。
  # mqadmin 每次调用要起 JVM(~4s),4 个 group 全采会把观测粒度拖到 20s+:
  # 交替采样——奇数循环采 chat 侧(up+signal),偶数循环采 connect 侧(s2c1+s2c2),粒度 ~10s。
  if [ $((SECONDS / 5 % 2)) -eq 0 ]; then
    UP=$(mq_diff "im-chat-consumer"); SIG=$(mq_diff "im-chat-deliver-consumer")
    UP_R=$(awk "BEGIN{printf \"%.1f\", ($UP-$PREV_UP)/5}"); SIG_R=$(awk "BEGIN{printf \"%.1f\", ($SIG-$PREV_SIG)/5}")
    PREV_UP=$UP; PREV_SIG=$SIG
    S2C1_R="-"; S2C2_R="-"
  else
    S2C1=$(mq_diff "$GRP_MSG1"); S2C2=$(mq_diff "$GRP_MSG2")
    S2C1_R=$(awk "BEGIN{printf \"%.1f\", ($S2C1-$PREV_S2C1)/5}"); S2C2_R=$(awk "BEGIN{printf \"%.1f\", ($S2C2-$PREV_S2C2)/5}")
    PREV_S2C1=$S2C1; PREV_S2C2=$S2C2
    UP_R="-"; SIG_R="-"
  fi

  # 节点连接数(Redis 实时值,nodeId 三处一致)
  NC1=$(redis GET "$NODE1" 2>/dev/null); NC2=$(redis GET "$NODE2" 2>/dev/null)
  NC1=${NC1:-0}; NC2=${NC2:-0}

  # 落库速率(Innodb_rows_inserted 差值/5s)
  ROWS=$(innodb_rows); [ -z "$ROWS" ] && ROWS=0
  INS_R=$(awk "BEGIN{printf \"%.1f\", ($ROWS-$PREV_ROWS)/5}")
  PREV_ROWS=$ROWS

  # DBSIZE 30s 一次(常量,避免每次 SCAN)
  if [ $((SECONDS - PREV_DBSIZE_T)) -ge 30 ]; then
    PREV_DBSIZE=$(redis DBSIZE 2>/dev/null); PREV_DBSIZE_T=$SECONDS
  fi

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$(date +%H:%M:%S)" "$CPU_USR" "$CPU_SYS" "$CPU_IDLE" "$LOAD1" \
    "$CPU_CHAT1" "$CPU_CHAT2" "$CPU_CONN1" "$CPU_CONN2" \
    "${OPS:-0}" "${BLOCKED:-0}" "${CLIENTS:-0}" "${MEM:-0}" \
    "$UP_R" "$SIG_R" "$S2C1_R" "$S2C2_R" "$NC1" "$NC2" "${INS_R:-0}" "${PREV_DBSIZE:-0}" >> "$OUT"
done

echo "观测完成: $OUT"
