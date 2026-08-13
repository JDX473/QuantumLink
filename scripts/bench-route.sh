#!/usr/bin/env bash
# QuantumLink 路由微基准(压测矩阵用例 C:指标③路由查询)
#
# 量化聊天链路里的两个 Redis 热点:
#   1. GET(会话表 im:session:* 查询,chat nodeOf 两跳的第一跳是 SMEMBERS + 第二跳是 N 次 GET)
#   2. SMEMBERS(im:devices:* 设备集合)
# 以及 nodeOf 组合模拟(SMEMBERS + 逐成员 GET,串行,与 DownstreamProducer 一致)。
#
# 用法: scripts/bench-route.sh
# 环境变量: IM_REDIS_HOST/PORT/PASSWORD
set -u

redis() { redis-cli -h "${IM_REDIS_HOST:-127.0.0.1}" -p "${IM_REDIS_PORT:-6379}" \
  ${IM_REDIS_PASSWORD:+-a "$IM_REDIS_PASSWORD" --no-auth-warning} "$@"; }

echo "==================== 路由微基准 ===================="
echo "[1/4] RTT 基线(redis-cli --latency, 2s)"
timeout 3 redis --latency 2>/dev/null | tail -1

echo "[2/4] GET 基准(会话表单查询)"
redis-benchmark -h "${IM_REDIS_HOST:-127.0.0.1}" -p "${IM_REDIS_PORT:-6379}" \
  ${IM_REDIS_PASSWORD:+-a "$IM_REDIS_PASSWORD" --no-auth-warning} \
  -t get -n 100000 -c 1 2>/dev/null | grep -i "requests per second"
redis-benchmark -h "${IM_REDIS_HOST:-127.0.0.1}" -p "${IM_REDIS_PORT:-6379}" \
  ${IM_REDIS_PASSWORD:+-a "$IM_REDIS_PASSWORD" --no-auth-warning} \
  -t get -n 100000 -c 50 2>/dev/null | grep -i "requests per second"
redis-benchmark -h "${IM_REDIS_HOST:-127.0.0.1}" -p "${IM_REDIS_PORT:-6379}" \
  ${IM_REDIS_PASSWORD:+-a "$IM_REDIS_PASSWORD" --no-auth-warning} \
  -t get -n 200000 -c 50 -P 16 2>/dev/null | grep -i "requests per second"

echo "[3/4] SMEMBERS 基准(设备集合,100 成员;本版 redis-benchmark -t smembers 无声 bug,改手写循环)"
redis del bench:dev >/dev/null 2>&1
for i in $(seq 1 100); do redis sadd bench:dev "dev_$i" >/dev/null; done
T0=$(date +%s%N)
for i in $(seq 1 20); do redis smembers bench:dev >/dev/null; done
T1=$(date +%s%N)
MS=$(( (T1 - T0) / 1000000 )); [ "$MS" -lt 1 ] && MS=1
echo "  20 次 SMEMBERS(100 成员,串行 spawn): ${MS}ms,平均 $(( 20 * 1000 / MS )) 次/s(含 spawn 开销,真实 Lettuce 连接池内更高)"
redis del bench:dev >/dev/null 2>&1

echo "[4/4] nodeOf 两跳组合模拟(与 DownstreamProducer 同构:SMEMBERS + 逐成员 GET,串行无 pipeline)"
redis del bench:devs >/dev/null 2>&1
for i in $(seq 1 100); do
  redis sadd bench:devs "d$i" >/dev/null
  redis set "bench:sess:100:d$i" "8.141.86.246:19001" >/dev/null
done
T0=$(date +%s%N)
DEVS=$(redis smembers bench:devs)
for d in $DEVS; do redis get "bench:sess:100:$d" >/dev/null; done
T1=$(date +%s%N)
MS=$(( (T1 - T0) / 1000000 )); [ "$MS" -lt 1 ] && MS=1
echo "  100 人两跳串行(redis-cli spawn 开销大,仅参考数量级): ${MS}ms ≈ $(( 100000 / MS )) GET/s 等效"
echo "  注: 真实实现是 Lettuce 连接池内串行(无 spawn 开销),数量级看 [2/4] GET 基准"
redis del bench:devs >/dev/null 2>&1
for i in $(seq 1 100); do redis del "bench:sess:100:d$i" >/dev/null 2>&1; done
echo "==============================================="
