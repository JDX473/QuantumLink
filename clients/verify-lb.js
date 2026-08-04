/**
 * 最少连接调度验证:
 * 1. 3 个用户连 19001(挂 3 条连接)→ 调度接口应返回 19002(连接数 0 < 3)
 * 2. 4 个用户连 19002(19001=3, 19002=4)→ 调度接口应切回 19001(3 < 4)
 *
 * 验证"服务端最少连接决策"真的在比较各节点实时连接数。
 * 依赖:Nacos(8850)+ Redis + chat(8081)+ connect(19001/19002)运行中。
 *
 * 用法: node verify-lb.js
 */
const { ImClient } = require('./client-core');
const API = 'http://127.0.0.1:8081';

async function registerOrLogin(username, password) {
  let r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, deviceType: 'desktop' }),
  });
  let data = await r.json();
  if (!data.success) {
    await fetch(API + '/api/auth/register', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    r = await fetch(API + '/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, deviceType: 'desktop' }),
    });
    data = await r.json();
  }
  return data; // { token, deviceId, userId }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function dispatch() {
  return (await (await fetch(API + '/api/connects')).json());
}

function connectTo(host, port, { token, deviceId }) {
  return new Promise((resolve, reject) => {
    const c = new ImClient({
      host, port, token, deviceId, deviceType: 'desktop',
      handlers: {
        onConnected: (uid) => { console.log(`  [${host}:${port}] connected, userId=${uid}`); resolve(c); },
      },
    });
    c.connect();
    setTimeout(() => reject(new Error(`connect timeout ${host}:${port}`)), 8000);
  });
}

async function main() {
  const names = ['lbA', 'lbB', 'lbC', 'lbD', 'lbE', 'lbF', 'lbG'];
  const users = [];
  for (const n of names) users.push(await registerOrLogin(n, 'pass123'));

  // 1. 3 个用户连 19001,19002 空着
  console.log('[1] 3 个用户连 19001...');
  for (let i = 0; i < 3; i++) await connectTo('127.0.0.1', 19001, users[i]);
  await sleep(3000); // 等连接数上报(1s 心跳)

  let d = await dispatch();
  console.log(`[1] 调度结果: address=${d.address} connections=${d.connections}`);
  if (d.address !== '127.0.0.1:19002') {
    console.error(`FAIL: 19001 挂 3 条,期望调度到 19002,实际 ${d.address}`);
    process.exit(1);
  }
  console.log('  ✓ 最少连接选到 19002(连接数 0 < 3)');

  // 2. 4 个用户连 19002 → 19002=4 > 19001=3,应切回 19001
  console.log('[2] 4 个用户连 19002...');
  for (let i = 3; i < 7; i++) await connectTo('127.0.0.1', 19002, users[i]);
  await sleep(3000);

  d = await dispatch();
  console.log(`[2] 调度结果: address=${d.address} connections=${d.connections}`);
  if (d.address !== '127.0.0.1:19001') {
    console.error(`FAIL: 19001=3 < 19002=4,期望调度到 19001,实际 ${d.address}`);
    process.exit(1);
  }
  console.log('  ✓ 最少连接切回 19001(3 < 4)');

  console.log('=== 最少连接调度验证通过 ===');
  process.exit(0);
}

main().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
