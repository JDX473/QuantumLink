/**
 * 登录注册验证:
 * 1. 注册两个随机用户(避开种子用户 alice/bob——schema.sql 预置 dev-only 密码)
 * 2. 用真实登录拿到 token + deviceId
 * 3. 用 token 握手连上 connect,互通消息
 *
 * 用法: node verify-auth.js
 */
const { ImClient } = require('./client-core');
const { newUser } = require('./test-lib');

async function main() {
  // 1. 注册 + 登录(随机用户名,多跑不冲突)
  console.log('=== 注册 + 登录 ===');
  const a = await newUser('authA');
  const b = await newUser('authB');
  console.log(`alice → token=${a.token.slice(0, 8)}... deviceId=${a.deviceId} userId=${a.userId}`);
  console.log(`bob   → token=${b.token.slice(0, 8)}... deviceId=${b.deviceId} userId=${b.userId}`);
  if (!a.success || !b.success) {
    console.error('登录失败:', a, b);
    process.exit(1);
  }

  // 2. 握手 + 互通
  console.log('\n=== 握手 + 互通 ===');
  let done = false;
  const alice = new ImClient({
    host: process.env.IM_CONNECT_HOST || '127.0.0.1', port: 19001,
    token: a.token, deviceId: a.deviceId, deviceType: 'desktop',
    handlers: { onConnected: () => console.log('[alice] 握手成功') },
  });
  const bob = new ImClient({
    host: process.env.IM_CONNECT_HOST || '127.0.0.1', port: 19001,
    token: b.token, deviceId: b.deviceId, deviceType: 'desktop',
    handlers: {
      onConnected: () => {
        console.log('[bob] 握手成功,alice 发消息');
        alice.sendMessage({ receiverId: b.userId, msgType: 'TEXT', content: 'hi bob', clientTime: Date.now() });
      },
      onMessage: (m) => {
        console.log(`[bob] 收到: "${m.content}" from=${m.senderId} seq=${m.seq}`);
        console.log('\n=== 登录注册 + 握手 + 互通 全流程通过 ===');
        done = true;
        process.exit(0);
      },
    },
  });
  alice.connect();
  setTimeout(() => bob.connect(), 300);

  setTimeout(() => { if (!done) { console.error('超时'); process.exit(1); } }, 15000);
}

main().catch(e => { console.error(e); process.exit(1); });
