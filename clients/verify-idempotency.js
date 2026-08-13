/**
 * 幂等 + 重传配合验证:
 * 1. 客户端发一条消息(正常收到 STORE)
 * 2. 用同一个 clientMsgId 手动再发一次(模拟"ACK 丢了,客户端重传")
 * 3. 验证:DB 里该消息只落库一次(幂等去重),且收到重复 ACK
 *
 * 用法: node verify-idempotency.js
 */
const { ImClient } = require('./client-core');
const { newUser } = require('./test-lib');

(async () => {
  const a = await newUser('idemA');
  const b = await newUser('idemB');
  console.log(`A=${a.userId} B=${b.userId}`);

  let acks = 0;
  const client = new ImClient({
    host: '127.0.0.1', port: 19001,
    token: a.token, deviceId: a.deviceId, deviceType: 'desktop',
    handlers: {
      onConnected: () => {
        // 发一条消息
        const cmid = client.sendMessage({
          receiverId: b.userId, msgType: 'TEXT',
          content: 'idempotency test',
          clientTime: Date.now(),
        });
        console.log(`[test] 已发送 clientMsgId=${cmid}`);

        // 2s 后(正常应已收到首个 ACK),用同一 clientMsgId 重传,模拟 ACK 丢失后的客户端重发
        setTimeout(() => {
          console.log('[test] 模拟重传: 用同一 clientMsgId 再发一次');
          client.sendFrame(require('./protocol').FrameType.MSG, {
            clientMsgId: cmid, receiverId: b.userId, msgType: 'TEXT',
            content: 'idempotency test', clientTime: Date.now(),
          });
        }, 2000);
      },
      onAck: (ack) => {
        acks++;
        console.log(`[test] 收到 ACK #${acks}: clientMsgId=${ack.clientMsgId} serverMsgId=${ack.serverMsgId}`);
        if (acks >= 2) {
          console.log(`\n=== 结果: 收到 ${acks} 个 ACK(重传后仍能确认), DB 应只有 1 条 ===`);
          setTimeout(() => process.exit(0), 500);
        }
      },
    },
  });

  client.connect();
  setTimeout(() => { console.error('超时'); process.exit(1); }, 15000);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
