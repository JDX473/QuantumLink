#!/usr/bin/env bash
# QuantumLink IM - 中间件一键安装(Ubuntu/Debian;其他发行版手动按 docs/云部署.md 第 3 节)
# 安装并启动:JDK17 + MySQL + Redis + RocketMQ + Nacos + MinIO(可选)
# 用法: bash scripts/install-middleware.sh
# 幂等:每个组件检测"已装/已监听"则跳过,可安全重跑;某组件失败打 [!!] 不中断后续。
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&- 3<&-; return 0; } || return 1; }

echo "==================== QuantumLink 中间件安装 ===================="
echo "  适用: Ubuntu/Debian(CentOS 请手动按 docs/云部署.md)"

# ---------- 0. JDK 17 ----------
if command -v java >/dev/null 2>&1; then
  echo "[ok ] JDK: $(java -version 2>&1 | head -1)"
else
  echo "[.. ] 安装 JDK 17 ..."
  sudo apt-get update -y && sudo apt-get install -y openjdk-17-jdk-headless
fi
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"

# ---------- 1. MySQL 8 ----------
if command -v mysql >/dev/null 2>&1; then
  echo "[ok ] MySQL 已装"
else
  echo "[.. ] 安装 MySQL ..."
  sudo apt-get install -y mysql-server
fi
sudo systemctl enable --now mysql 2>/dev/null || sudo service mysql start 2>/dev/null || true
# 设 root 密码 + 建库(幂等)
sudo mysql -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '123456'; FLUSH PRIVILEGES;" 2>/dev/null || true
mysql -uroot -p123456 < "$ROOT/sql/schema.sql" 2>/dev/null \
  && echo "[ok ] MySQL 库表已初始化" || echo "[!! ] MySQL 初始化失败(手动: mysql -uroot -p123456 < sql/schema.sql)"

# ---------- 2. Redis ----------
if command -v redis-server >/dev/null 2>&1; then
  echo "[ok ] Redis 已装"
else
  echo "[.. ] 安装 Redis ..."
  sudo apt-get install -y redis-server
fi
grep -q "^maxmemory" /etc/redis/redis.conf 2>/dev/null \
  || echo -e "maxmemory 1gb\nmaxmemory-policy allkeys-lru" | sudo tee -a /etc/redis/redis.conf >/dev/null
sudo systemctl enable --now redis-server 2>/dev/null || sudo service redis-server start 2>/dev/null || true
port_open 6379 && echo "[ok ] Redis :6379" || echo "[!! ] Redis 未起"

# ---------- 3. RocketMQ 5.3.1 ----------
RMQ=/opt/rocketmq-all-5.3.1-bin-release
if [ ! -d "$RMQ" ]; then
  echo "[.. ] 下载 RocketMQ 5.3.1 ..."
  cd /opt
  sudo wget -q https://dist.apache.org/repos/dist/release/rocketmq/5.3.1/rocketmq-all-5.3.1-bin-release.zip || { echo "[!! ] RocketMQ 下载失败(换镜像或手动)"; }
  [ -f rocketmq-all-5.3.1-bin-release.zip ] && sudo unzip -q rocketmq-all-5.3.1-bin-release.zip
fi
if [ -d "$RMQ" ]; then
  cd "$RMQ"
  # broker 固定 127.0.0.1 + 改小 JVM 堆
  cat > conf/broker-local.conf <<'EOF'
brokerClusterName = DefaultCluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
brokerIP1 = 127.0.0.1
EOF
  sed -i 's/-Xms4g/-Xms256m/; s/-Xmx4g/-Xmx512m/' bin/runserver.sh 2>/dev/null
  sed -i 's/-Xms8g/-Xms256m/; s/-Xmx8g/-Xmx512m/' bin/runbroker.sh 2>/dev/null
  port_open 9876 || { nohup sh bin/mqnamesrv > namesrv.log 2>&1 & echo "[.. ] namesrv 启动中"; }
  sleep 5
  port_open 10911 || { nohup sh bin/mqbroker -n 127.0.0.1:9876 -c conf/broker-local.conf > broker.log 2>&1 & echo "[.. ] broker 启动中"; }
  sleep 5
fi
port_open 9876 && echo "[ok ] RocketMQ-namesrv :9876" || echo "[!! ] namesrv 未起(看 $RMQ/namesrv.log)"
port_open 10911 && echo "[ok ] RocketMQ-broker :10911" || echo "[!! ] broker 未起(看 $RMQ/broker.log)"

# ---------- 4. Nacos 2.5.0 ----------
if [ ! -d /opt/nacos ]; then
  echo "[.. ] 下载 Nacos 2.5.0 ..."
  cd /opt
  sudo wget -q https://github.com/alibaba/nacos/releases/download/2.5.0/nacos-server-2.5.0.tar.gz || { echo "[!! ] Nacos 下载失败(换镜像或手动)"; }
  [ -f nacos-server-2.5.0.tar.gz ] && sudo tar -xzf nacos-server-2.5.0.tar.gz
fi
if [ -d /opt/nacos ]; then
  port_open 8848 || sh /opt/nacos/bin/startup.sh -m standalone
  sleep 5
fi
port_open 8848 && echo "[ok ] Nacos :8848" || echo "[!! ] Nacos 未起(看 /opt/nacos/logs/start.out)"

# ---------- 5. MinIO(可选,头像功能) ----------
if [ -f /usr/local/bin/minio ]; then
  echo "[ok ] MinIO 已装"
else
  echo "[.. ] 下载 MinIO ..."
  sudo wget -q https://dl.min.io/server/minio/release/linux-amd64/minio -O /usr/local/bin/minio || { echo "[!! ] MinIO 下载失败(可选,不装也能启动 chat)"; }
  sudo chmod +x /usr/local/bin/minio 2>/dev/null
  sudo mkdir -p /data/minio
fi
if [ -f /usr/local/bin/minio ] && ! port_open 9000; then
  MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin \
    nohup minio server /data/minio --address ":9000" --console-address ":9001" >/data/minio/minio.log 2>&1 &
  sleep 3
fi
port_open 9000 && echo "[ok ] MinIO :9000" || echo "[-- ] MinIO 未起(可选,头像功能才需要)"

# ---------- 6. 系统参数 ----------
sudo sysctl -w net.core.somaxconn=1024 >/dev/null 2>&1
ulimit -n 65535 2>/dev/null

echo "==================== 安装完成 ===================="
echo "  检查: ss -ltn | grep -E '3306|6379|9876|10911|8848|9000'"
echo "  下一步:"
echo "    export IM_NACOS_ADDR=127.0.0.1:8848"
echo "    bash scripts/start-all.sh"
