#!/usr/bin/env bash
# QuantumLink IM - stop im-chat + im-connect(不动 MySQL/Redis/RocketMQ/Nacos/MinIO)
# 用法: scripts/stop-all.sh
set -u

echo "==================== QuantumLink stop ===================="
pkill -f "im-chat-1.0.0-SNAPSHOT.jar" && echo "[stop] im-chat" || echo "[--] im-chat 未在运行"
pkill -f "im-connect-1.0.0-SNAPSHOT.jar" && echo "[stop] im-connect" || echo "[--] im-connect 未在运行"
sleep 2
echo "==================== Done ===================="
