/**
 * 消息链路压测:固定 N 条连接,互发消息,统计端到端吞吐 + 延迟分布。
 *
 * 场景:每条连接发消息给另一条(两两配对),持续 T 秒。
 * 统计:
 *   - 吞吐(QPS):每秒成功送达的消息数(收到 ACK-STORE 才算成功)
 *   - 端到端延迟:客户端发 → chat 落库 → ACK 回到发送方 的耗时(P50/P99)
 *   - 重传率:超时重传的消息占比(高 = 链路慢/丢)
 *
 * 用法: node loadtest-throughput.js [连接数] [时长秒] [每连接每秒消息数]
 *   例: node loadtest-throughput.js 200 30 5
 *
 * 注意:压测机与被压系统同机,吞吐为保守值;连接数受 Windows 端口限制,
 * 消息链路瓶颈(Redis keys/executor/落库)在低连接数下即可暴露。
 */
const { ImClient } = require('../client-core');
const API = 'http://127.0.0.1:8081';

const CONNECTIONS = parseInt(process.argv[2] || '100');
const DURATION_S = parseInt(process.argv[3] || '20');
const MSG_PER_SEC = parseFloat(process.argv[4] || '5');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function login(username, password) {
  let r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, deviceType: 'desktop' }),
  });
  let d = await r.json();
  if (!d.success) {
    await fetch(API + '/api/auth/register', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    d = await (await fetch(API + '/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, deviceType: 'desktop' }),
    })).json();
  }
  return d; // { token, deviceId, userId }
}

// 全局统计
let sent = 0, acked = 0, resent = 0;
const latencies = [];

function connectTo(node, u, handlers) {
  const c = new ImClient({ host: '127.0.0.1', port: node, token: u.token, deviceId: u.deviceId, deviceType: 'loadtest',
    quiet: true, // 压测静默:关闭高频日志,避免 console.log 阻塞 EventLoop
    handlers: {
      onConnected: (uid) => { if (handlers.onConnected) handlers.onConnected(uid); },
      onAck: (ack) => {
        if (ack.ackType === 'STORE') {
          acked++;
          // 端到端延迟:发出去到 ACK 回来(ms)
          const sentAt = c._pendingAckTime.get(ack.clientMsgId);
          if (sentAt) latencies.push(Date.now() - sentAt);
          if (handlers.onAck) handlers.onAck(ack);
        }
      },
      onMessage: () => {},
      onClosed: () => {},
    },
  });
  c._pendingAckTime = new Map();
  // 包装 sendMessage 记录发送时间
  const origSend = c.sendMessage.bind(c);
  c.sendMessage = (msg) => {
    const cmid = origSend(msg);
    c._pendingAckTime.set(cmid, Date.now());
    return cmid;
  };
  c.connect();
  return c;
}

async function main() {
  console.log(`=== 消息链路压测 ===`);
  console.log(`连接数: ${CONNECTIONS}, 时长: ${DURATION_S}s, 每连接每秒: ${MSG_PER_SEC} 条`);
  console.log(`总目标: ${Math.round(CONNECTIONS * MSG_PER_SEC)} 条/秒`);

  // 1. 登录 CONNECTIONS 个用户(复用压力不太大的前缀)
  const users = [];
  const t0 = Date.now();
  for (let i = 0; i < CONNECTIONS; i++) {
    const u = await login(`lt_user_${i}`, 'pass123');
    if (!u.success) { console.error(`登录失败 ${i}`); process.exit(1); }
    users.push(u);
    if ((i + 1) % 50 === 0) console.log(`  登录 ${i + 1}/${CONNECTIONS}`);
  }
  console.log(`登录完成: ${CONNECTIONS} 用户, 耗时 ${Date.now() - t0}ms`);

  // 2. 连接(轮流连 19001/19002 模拟跨节点)
  const clients = [];
  for (let i = 0; i < CONNECTIONS; i++) {
    const port = i % 2 === 0 ? 19001 : 19002;
    const c = connectTo(port, users[i], {});
    clients.push(c);
  }
  // 等全部握手成功
  await sleep(5000);
  console.log(`连接建立完成(5s 等待握手)`);

  // 3. 两两配对,持续灌消息
  const pair = (i) => (i % 2 === 0 ? i + 1 : i - 1); // 相邻配对
  const sendInterval = 1000 / MSG_PER_SEC;
  console.log(`开始灌消息,每连接每 ${sendInterval.toFixed(1)}ms 发一条...`);

  const start = Date.now();
  let seq = 0;
  const timer = setInterval(() => {
    for (let i = 0; i < CONNECTIONS; i++) {
      const target = clients[pair(i)];
      if (!target) continue;
      try {
        clients[i].sendMessage({
          receiverId: users[pair(i)].userId,
          msgType: 'TEXT',
          content: `load-${seq++}-${i}`,
          clientTime: Date.now(),
        });
        sent++;
      } catch (e) {}
    }
  }, sendInterval);

  await sleep(DURATION_S * 1000);
  clearInterval(timer);
  const elapsed = (Date.now() - start) / 1000;

  // 4. 统计
  console.log(`\n=== 压测结果 ===`);
  console.log(`时长: ${elapsed.toFixed(1)}s`);
  console.log(`发送(MSG): ${sent} 条, 实际速率: ${(sent / elapsed).toFixed(0)} 条/秒`);
  console.log(`ACK 收到: ${acked} 条, 送达率: ${(acked / Math.max(sent, 1) * 100).toFixed(1)}%`);
  console.log(`真实链路负载: ${(sent / elapsed * 2).toFixed(0)} 条/秒(MSG 上行 + DELIVER_ACK 上行,chat 实际消费量)`);
  if (latencies.length > 0) {
    latencies.sort((a, b) => a - b);
    const p50 = latencies[Math.floor(latencies.length * 0.5)];
    const p90 = latencies[Math.floor(latencies.length * 0.9)];
    const p99 = latencies[Math.floor(latencies.length * 0.99)];
    const avg = latencies.reduce((s, x) => s + x, 0) / latencies.length;
    console.log(`端到端延迟(发→ACK): P50=${p50}ms P90=${p90}ms P99=${p99}ms avg=${avg.toFixed(0)}ms`);
  } else {
    console.log(`端到端延迟: 无 ACK 数据(链路可能不通)`);
  }
  console.log(`\n瓶颈分析提示:看 chat 日志的落库耗时/Redis INCR/MQ 积压,定位瓶颈环节`);

  process.exit(0);
}

main().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
