/**
 * 一次性验证脚本:握手 + 发消息,确认 connect 层工作正常。
 * 用法: node verify-connect.js
 */
const { ImClient } = require('./client-core');

const client = new ImClient({
  host: '127.0.0.1',
  port: 19001,
  token: 'test-token-123',
  deviceId: 'D-verify',
  deviceType: 'desktop',
  handlers: {
    onConnected: (userId) => {
      console.log(`✓ 握手成功, userId=${userId}`);
      // 发一条测试消息
      const ok = client.sendMessage({
        clientMsgId: `D-verify-${Date.now()}`,
        receiverId: 'user-B',
        msgType: 'TEXT',
        content: 'hello from verify',
        clientTime: Date.now(),
      });
      console.log(`✓ 消息已发送: ${ok}`);
      setTimeout(() => { client.close(); process.exit(0); }, 1500);
    },
    onAck: (ack) => console.log(`收到 ACK: type=${ack.ackType} serverMsgId=${ack.serverMsgId}`),
    onMessage: (m) => console.log(`收到消息: ${m.content}`),
    onError: (e) => { console.error(`握手失败: ${e.code} ${e.message}`); process.exit(1); },
    onClosed: () => console.log('连接已关闭'),
  },
});

client.connect();
// 12s 后超时退出
setTimeout(() => { console.error('超时:未连接成功'); process.exit(1); }, 12000);
