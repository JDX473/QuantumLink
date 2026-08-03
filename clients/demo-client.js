/**
 * QuantumLink 演示客户端(命令行)。
 *
 * 用法:
 *   node demo-client.js <token> <deviceId> <deviceType>
 * 示例:
 *   node demo-client.js test-token-123 D-1 desktop
 *
 * 连接后:
 *   - 自动握手、心跳(10s)
 *   - stdin 输入消息发给接收方(JSON: {receiverId, content})
 *   - 收到下行消息 / ACK 打印到 stdout
 */
const readline = require('readline');
const { ImClient } = require('./client-core');

const [,, token = 'test-token-123', deviceId = 'D-1', deviceType = 'desktop'] = process.argv;

const client = new ImClient({
  host: '127.0.0.1',
  port: 9999,
  token,
  deviceId,
  deviceType,
  handlers: {
    onConnected: (userId) => {
      console.log(`已连接, userId=${userId}`);
      console.log('输入消息发送(JSON: {"receiverId":"user-B","content":"hi"}),q 退出');
    },
    onMessage: (msg) => {
      console.log(`\n[收到] ${msg.senderId}: ${msg.content}  (seq=${msg.seq})`);
    },
    onAck: (ack) => {
      console.log(`\n[ACK] type=${ack.ackType} serverMsgId=${ack.serverMsgId} seq=${ack.seq}`);
    },
    onError: (e) => console.log(`\n[服务端错误] ${e.code}: ${e.message}`),
    onClosed: () => console.log('\n[连接断开]'),
  },
});

client.connect();

// 命令行输入
const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
rl.on('line', (line) => {
  const text = line.trim();
  if (text === 'q') {
    client.close();
    process.exit(0);
  }
  try {
    const msg = JSON.parse(text);
    client.sendMessage({
      clientMsgId: `${deviceId}-${Date.now()}-${Math.floor(Math.random() * 1000)}`,
      conversationId: msg.conversationId || undefined,
      receiverId: msg.receiverId,
      senderId: undefined, // 服务端填充
      msgType: msg.msgType || 'TEXT',
      content: msg.content,
      clientTime: Date.now(),
    });
  } catch (e) {
    console.error('输入格式错误,应为 JSON');
  }
});

process.on('SIGINT', () => { client.close(); process.exit(0); });
