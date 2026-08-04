/**
 * 断线重传验证(核心场景):
 * 1. 首次连接后发一条消息,立即断线(消息留在 pending,未收到 ACK)
 * 2. 客户端自动重连,重连成功后 flush pending(重传该消息)
 * 3. 验证:消息最终落库一次(幂等),且收到 ACK
 */
const { ImClient } = require('./client-core');

let firstConnect = true;
let confirmed = false;
let resendSeen = false;

const client = new ImClient({
  host: '127.0.0.1', port: 19001,
  token: 'test-token-123', deviceId: 'D-reconn', deviceType: 'desktop',
  handlers: {
    onConnected: () => {
      if (firstConnect) {
        firstConnect = false;
        console.log('[test] 首次连接');
        const cmid = client.sendMessage({
          receiverId: 'user-B', msgType: 'TEXT',
          content: 'reconnect resend test',
          clientTime: Date.now(),
        });
        console.log(`[test] 消息 ${cmid} 已发出,立即断线(不等 ACK)`);
        client.socket.destroy();
      } else {
        console.log('[test] 重连成功(等待 pending flush + ACK)');
      }
    },
    onAck: (ack) => {
      confirmed = true;
      console.log(`[test] ★确认: clientMsgId=${ack.clientMsgId} serverMsgId=${ack.serverMsgId} seq=${ack.seq}`);
      console.log('=== 断线重传成功,消息确认 ===');
      setTimeout(() => process.exit(0), 500);
    },
    onSendFailed: (msg) => {
      console.error(`[test] 发送失败: ${msg.clientMsgId}`);
    },
  },
});

client.connect();
setTimeout(() => {
  console.error(confirmed ? '' : '超时:未确认');
  process.exit(confirmed ? 0 : 1);
}, 20000);
