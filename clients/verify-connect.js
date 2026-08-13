/**
 * 一次性验证脚本:握手 + 发消息,确认 connect 层工作正常。
 * A 注册登录后连接,发消息给 B(离线,只验证消息发出并拿到 STORE 回执)。
 *
 * 用法: node verify-connect.js
 */
const { ImClient } = require('./client-core');
const { newUser } = require('./test-lib');

(async () => {
  const a = await newUser('connA');
  const b = await newUser('connB');
  console.log(`A=${a.userId} B=${b.userId}(离线,只验证发送链路)`);

  let storeOk = false;
  const client = new ImClient({
    host: '127.0.0.1',
    port: 19001,
    token: a.token,
    deviceId: a.deviceId,
    deviceType: 'desktop',
    handlers: {
      onConnected: (userId) => {
        console.log(`✓ 握手成功, userId=${userId}`);
        // 发一条测试消息
        const ok = client.sendMessage({
          clientMsgId: `D-verify-${Date.now()}`,
          receiverId: b.userId,
          msgType: 'TEXT',
          content: 'hello from verify',
          clientTime: Date.now(),
        });
        console.log(`✓ 消息已发送: ${ok}`);
      },
      onAck: (ack) => {
        if (ack.ackType === 'STORE') {
          storeOk = true;
          console.log(`✓ 收到 STORE 回执: serverMsgId=${ack.serverMsgId} seq=${ack.seq}`);
          console.log('=== connect 层发送链路正常 ===');
          process.exit(0);
        }
      },
      onMessage: (m) => console.log(`收到消息: ${m.content}`),
      onError: (e) => { console.error(`握手失败: ${e.code} ${e.message}`); process.exit(1); },
      onClosed: () => console.log('连接已关闭'),
    },
  });

  client.connect();
  // 12s 后超时退出
  setTimeout(() => { console.error(storeOk ? '' : '超时:未连接成功'); process.exit(storeOk ? 0 : 1); }, 12000);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
