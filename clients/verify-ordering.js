/**
 * 有序性验证:同一客户端快速连续发 5 条消息,
 * 验证服务端分配的 seq 严格递增且与发送顺序一致。
 *
 * 用法: node verify-ordering.js
 */
const { ImClient } = require('./client-core');

const sentOrder = [];
const acked = [];

const client = new ImClient({
  host: '127.0.0.1', port: 9999,
  token: 'test-token-123', deviceId: 'D-ord', deviceType: 'desktop',
  handlers: {
    onConnected: () => {
      console.log('[ord] 已连接,连续发 5 条');
      for (let i = 1; i <= 5; i++) {
        const cmid = client.sendMessage({
          receiverId: 'user-B', msgType: 'TEXT',
          content: `msg ${i}`,
          clientTime: Date.now(),
        });
        sentOrder.push(cmid);
      }
    },
    onAck: (ack) => {
      acked.push({ cmid: ack.clientMsgId, seq: ack.seq, serverMsgId: ack.serverMsgId });
      if (acked.length >= 5) {
        console.log('\n=== 发送顺序 vs 确认顺序 ===');
        sentOrder.forEach((c, i) => {
          const a = acked.find(x => x.cmid === c);
          console.log(`  发送#${i + 1}: ${c}  → seq=${a ? a.seq : '?'}`);
        });
        // 验证 seq 严格递增
        const seqs = acked.map(a => a.seq);
        const strictlyIncreasing = seqs.every((s, i) => i === 0 || s > seqs[i - 1]);
        console.log(`\nseq 列表: [${seqs.join(', ')}]`);
        console.log(strictlyIncreasing ? '✅ seq 严格递增' : '❌ seq 未严格递增(有序性被破坏!)');
        process.exit(strictlyIncreasing ? 0 : 1);
      }
    },
  },
});

client.connect();
setTimeout(() => { console.error('超时'); process.exit(1); }, 20000);
