/**
 * 双向互聊验证:两个客户端(user-A 和 user-B)都连上,
 * A 发消息给 B,B 应实时收到;同时验证 A 收到 ACK-STORE。
 *
 * 用法: node verify-chat.js
 */
const { ImClient } = require('./client-core');

// 两个客户端,不同 token 对应不同 user_id(需在 Redis 预置 token)
// 这里假设 user-A 用 test-token-123,user-B 用 test-token-456
const clientA = new ImClient({
  host: '127.0.0.1', port: 19001,
  token: 'test-token-123', deviceId: 'D-A', deviceType: 'desktop',
  handlers: {
    onConnected: (userId) => console.log(`[A] 已连接 userId=${userId}`),
    onAck: (ack) => console.log(`[A] 收到ACK type=${ack.ackType} serverMsgId=${ack.serverMsgId} seq=${ack.seq}`),
    onMessage: (m) => console.log(`[A] 收到消息 ${m.senderId}: ${m.content}`),
    onError: (e) => console.log(`[A] 错误 ${e.code}`),
  },
});

const clientB = new ImClient({
  host: '127.0.0.1', port: 19001,
  token: 'test-token-456', deviceId: 'D-B', deviceType: 'desktop',
  handlers: {
    onConnected: (userId) => {
      console.log(`[B] 已连接 userId=${userId}`);
      // B 连上后,A 发消息给 B
      setTimeout(() => {
        console.log('[A] 发送消息给 B...');
        clientA.sendMessage({
          clientMsgId: `D-A-${Date.now()}`,
          receiverId: 'user-B',
          msgType: 'TEXT',
          content: 'hello B, this is A!',
          clientTime: Date.now(),
        });
      }, 500);
    },
    onMessage: (m) => {
      console.log(`[B] ★收到消息 ${m.senderId}: ${m.content} (seq=${m.seq})`);
      console.log('=== 双向链路验证成功 ===');
      process.exit(0);
    },
    onError: (e) => console.log(`[B] 错误 ${e.code}`),
  },
});

clientA.connect();
clientB.connect();

setTimeout(() => { console.error('超时:未完成双向验证'); process.exit(1); }, 15000);
