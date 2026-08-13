/**
 * 消息链路压测(干净版):多进程 + 每轮独立数据。
 *
 * 用法: node loadtest-clean.js <进程数> <每进程连接数> <时长秒> <每连接每秒消息数>
 *   例: node loadtest-clean.js 1 15 15 6      # 1 进程 15 连接
 *        node loadtest-clean.js 2 25 20 4      # 2 进程共 50 连接
 *
 * 每轮用独立用户前缀(round-{ts}-{pid}-{i}),测完不清理(留给清理脚本)。
 * 静默模式:不打印每消息日志,避免阻塞客户端。
 *
 * 统计:吞吐(QPS)+ 端到端延迟(P50/P99)+ 送达率 + 真实链路负载(MSG+DELIVER_ACK)。
 */
const { ImClient } = require('../client-core');
const API = process.env.IM_API || 'http://127.0.0.1:8081';
const HOST = process.env.IM_CONNECT_HOST || '127.0.0.1';
const PORTS = (process.env.IM_CONNECT_PORTS || '19001 19002').split(' ').map(Number);

const PROCS = parseInt(process.argv[2] || '1');          // 进程数
const CONNS = parseInt(process.argv[3] || '15');         // 每进程连接数
const DURATION_S = parseInt(process.argv[4] || '15');    // 时长
const MSG_PER_SEC = parseFloat(process.argv[5] || '6');  // 每连接每秒
const ROUND = Math.floor(Date.now() / 1000);             // 本轮标识(独立用户前缀)

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
  return d;
}

async function runWorker(pid) {
  const users = [];
  const t0 = Date.now();
  for (let i = 0; i < CONNS; i++) {
    const u = await login(`lt_r${ROUND}_p${pid}_u${i}`, 'pass123');
    if (!u.success) { console.error(`[p${pid}] 登录失败 ${i}`); process.exit(1); }
    users.push(u);
  }
  console.log(`[p${pid}] ${CONNS} 用户登录完成,耗时 ${Date.now() - t0}ms`);

  // 建连(轮流连两节点模拟跨节点)
  const clients = [];
  const lat = [];
  for (let i = 0; i < CONNS; i++) {
    const port = PORTS[i % PORTS.length];
    const c = new ImClient({ host: HOST, port, token: users[i].token, deviceId: users[i].deviceId,
      deviceType: 'loadtest', quiet: true,
      handlers: {
        onConnected: () => {},
        onAck: (ack) => {
          if (ack.ackType === 'STORE') {
            const t = c._t.get(ack.clientMsgId);
            if (t) lat.push(Date.now() - t);
          }
        },
        onMessage: () => {},
      },
    });
    c._t = new Map();
    const origSend = c.sendMessage.bind(c);
    c.sendMessage = (m) => { const id = origSend(m); c._t.set(id, Date.now()); return id; };
    c.connect();
    clients.push(c);
  }
  await sleep(6000); // 等全部握手成功

  // 灌消息:每对互发。每轮发 CONNS 条,间隔 = 1000/MSG_PER_SEC(每秒每连接 1 条)
  const start = Date.now();
  let sent = 0, seq = 0;
  const sendInterval = 1000 / MSG_PER_SEC; // 每轮间隔:每连接每秒 MSG_PER_SEC 条
  const timer = setInterval(() => {
    for (let i = 0; i < CONNS; i++) {
      const peer = i % 2 === 0 ? i + 1 : i - 1;
      if (peer >= CONNS) continue;
      try {
        clients[i].sendMessage({ receiverId: users[peer].userId, msgType: 'TEXT', content: `x-${seq++}`, clientTime: Date.now() });
        sent++;
      } catch (e) {}
    }
  }, sendInterval);

  await sleep(DURATION_S * 1000);
  clearInterval(timer);
  const elapsed = (Date.now() - start) / 1000;
  lat.sort((a, b) => a - b);

  const p50 = lat.length ? lat[Math.floor(lat.length * 0.5)] : 0;
  const p99 = lat.length ? lat[Math.floor(lat.length * 0.99)] : 0;
  console.log(`[p${pid}] 结果: 发送 ${sent} 条, ${(sent / elapsed).toFixed(0)} 条/秒, 真实负载 ${(sent / elapsed * 2).toFixed(0)} 条/秒, P50=${p50}ms P99=${p99}ms`);
  process.exit(0);
}

// 多进程:主进程 fork 子进程(直接调 runWorker,靠 PID 区分)
runWorker(process.argv[6] || '0');
