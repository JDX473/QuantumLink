/**
 * 有序性验证:同一客户端快速连续发 5 条消息,
 * 验证服务端分配的 seq 严格递增且与发送顺序一致。
 *
 * 用法: node verify-ordering.js
 */
const { ImClient } = require('./client-core');
const { newUser } = require('./test-lib');

(async () => {
  const a = await newUser('ordA');
  const b = await newUser('ordB');
  console.log(`A=${a.userId} B=${b.userId}`);

  const sentOrder = [];
  const acked = [];

  const client = new ImClient({
    host: '127.0.0.1', port: 19001,
    token: a.token, deviceId: a.deviceId, deviceType: 'desktop',
    handlers: {
      onConnected: () => {
        console.log('[ord] 已连接,连续发 5 条');
        for (let i = 1; i <= 5; i++) {
          const cmid = client.sendMessage({
            receiverId: b.userId, msgType: 'TEXT',
            content: `msg ${i}`,
            clientTime: Date.now(),
          });
          sentOrder.push(cmid);
        }
      },
      onAck: (ack) => {
        acked.push({ cmid: ack.clientMsgId, seq: ack.seq, serverMsgId: ack.serverMsgId });
        if (acked.length >= 5) {
          console.log('\n=== 发送顺序 vs seq 分配 ===');
          sentOrder.forEach((c, i) => {
            const a = acked.find(x => x.cmid === c);
            console.log(`  发送#${i + 1}: ${c}  → seq=${a ? a.seq : '?'}`);
          });
          // 按 clientMsgId 对齐:每个发送的 clientMsgId 应拿到递增的 seq(1,2,3,4,5)
          // 注意:ACK 到达顺序是乱的(并发推送),所以不能按到达顺序判断,
          // 要按 clientMsgId 对齐看它拿到的 seq 是否正确
          const seqByClient = sentOrder.map(c => {
            const a = acked.find(x => x.cmid === c);
            return a ? a.seq : null;
          });
          const allAcked = seqByClient.every(s => s !== null && s !== undefined);
          const strictlyIncreasing = seqByClient.every((s, i) =>
            s !== null && (i === 0 || s > seqByClient[i - 1]));
          console.log(`\n按发送顺序对齐的 seq: [${seqByClient.join(', ')}]`);
          console.log(allAcked ? '✅ 所有消息都收到 ACK' : '❌ 有消息没收到 ACK');
          console.log(strictlyIncreasing ? '✅ seq 严格递增且与发送顺序一致' : '❌ seq 未严格递增(有序性被破坏!)');
          process.exit(allAcked && strictlyIncreasing ? 0 : 1);
        }
      },
    },
  });

  client.connect();
  setTimeout(() => { console.error('超时'); process.exit(1); }, 20000);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
