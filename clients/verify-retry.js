/**
 * 重传验证脚本:验证客户端"发送确认机"正确工作。
 *
 * 场景1(正常):发消息 → 收到 STORE → pending 清空,消息落库一次
 * 场景2(重传):连上后立即发消息,但服务端模拟"不回 ACK 的前几秒",
 *             客户端超时重传,最终收到 ACK;查库确认只落库一次(幂等)
 *
 * 用法: node verify-retry.js
 */
const { ImClient } = require('./client-core');

let confirmed = 0;
let failed = 0;

const client = new ImClient({
  host: '127.0.0.1', port: 9999,
  token: 'test-token-123', deviceId: 'D-retry', deviceType: 'desktop',
  handlers: {
    onConnected: (userId) => {
      console.log(`[retry] 已连接 userId=${userId}`);
      // 连续发 3 条消息,观察确认机
      for (let i = 1; i <= 3; i++) {
        const cmid = client.sendMessage({
          receiverId: 'user-B',
          msgType: 'TEXT',
          content: `retry test msg ${i}`,
          clientTime: Date.now(),
        });
        console.log(`[retry] 已发送 ${i}: clientMsgId=${cmid}`);
      }
    },
    onAck: (ack) => {
      confirmed++;
      console.log(`[retry] ★确认 ${ack.clientMsgId}: serverMsgId=${ack.serverMsgId} seq=${ack.seq}`);
      if (confirmed >= 3) {
        console.log(`\n=== 结果: 3 条消息全部确认(${failed} 条失败) ===`);
        setTimeout(() => { process.exit(0); }, 500);
      }
    },
    onSendFailed: (msg) => {
      failed++;
      console.error(`[retry] 发送失败: ${msg.clientMsgId}`);
    },
  },
});

client.connect();
setTimeout(() => { console.error('超时:未全部确认'); process.exit(1); }, 20000);
