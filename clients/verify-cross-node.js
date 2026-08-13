/**
 * 跨节点投递验证:
 * 1. 调调度接口拿节点列表
 * 2. 用户A 连 19001,用户B 连 19002(不同节点)
 * 3. A 发消息给 B → 验证跨节点 MQ tag 精准投递,B 能收到
 *
 * 用法: node verify-cross-node.js
 */
const { ImClient } = require('./client-core');
const API = process.env.IM_API || 'http://127.0.0.1:8081';

async function login(username, password) {
  const r = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, deviceType: 'desktop' }),
  });
  return r.json();
}

async function main() {
  // 1. 登录两个用户
  const a = await login('crossnodeA', 'pass123');
  const b = await login('crossnodeB', 'pass123');
  if (!a.success) { await (await fetch(API + '/api/auth/register', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:'crossnodeA',password:'pass123'}) })).json(); }
  if (!b.success) { await (await fetch(API + '/api/auth/register', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:'crossnodeB',password:'pass123'}) })).json(); }
  const a2 = await login('crossnodeA', 'pass123');
  const b2 = await login('crossnodeB', 'pass123');
  console.log(`A: ${a2.userId}  B: ${b2.userId}`);

  // 1.5 调度接口(新接口:服务端最少连接决策,返回单个节点;需鉴权)
  const dispatch = await (await fetch(API + '/api/connects', { headers: { Authorization: 'Bearer ' + a2.token } })).json();
  console.log('调度接口最少连接节点:', dispatch.address, '(本脚本强制连 19001/19002 验证跨节点)');

  // 3. A 连 19001,B 连 19002(强制不同节点)
  let done = false;
  const ca = new ImClient({
    host: process.env.IM_CONNECT_HOST || '127.0.0.1', port: 19001, token: a2.token, deviceId: a2.deviceId, deviceType: 'desktop',
    handlers: { onConnected: (uid) => console.log('[A] 已连 19001, userId=', uid) },
  });
  const cb = new ImClient({
    host: process.env.IM_CONNECT_HOST || '127.0.0.1', port: 19002, token: b2.token, deviceId: b2.deviceId, deviceType: 'desktop',
    handlers: {
      onConnected: (uid) => {
        console.log('[B] 已连 19002, userId=', uid);
        console.log('[A] 发消息给 B(跨节点)...');
        ca.sendMessage({ receiverId: b2.userId, msgType: 'TEXT', content: '跨节点消息测试', clientTime: Date.now() });
      },
      onMessage: (m) => {
        console.log(`[B] ★收到跨节点消息: "${m.content}" from=${m.senderId} seq=${m.seq}`);
        done = true;
        console.log('=== 跨节点投递验证通过 ===');
        process.exit(0);
      },
    },
  });
  ca.connect();
  setTimeout(() => cb.connect(), 500);

  setTimeout(() => { if (!done) { console.error('超时: 跨节点消息未到达'); process.exit(1); } }, 15000);
}

main().catch(e => { console.error(e); process.exit(1); });
