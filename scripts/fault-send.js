// 故障注入用固定用户脚本：faultA 给 faultB 发 N 条消息（同一会话，seq 连续累加）
// 用法：node scripts/fault-send.js <count>
const { loginOrRegister, sleep } = require('../clients/test-lib');
const { ImClient } = require('../clients/client-core');

const USER_A = 'faultA_user';
const USER_B = 'faultB_user';

async function main() {
  const count = parseInt(process.argv[2] || '3', 10);
  const A = await loginOrRegister(USER_A);
  const B = await loginOrRegister(USER_B);
  console.log('userA:', A.userId, 'userB:', B.userId);

  const clientB = new ImClient({
    host: '127.0.0.1', port: 19001, token: B.token, deviceId: B.deviceId, quiet: true,
    handlers: { onMessage: (m) => { if (m.conversationId) console.log('[B recv]', m.serverMsgId, 'seq', m.seq); } },
  });
  clientB.connect();
  await sleep(1200);

  const clientA = new ImClient({
    host: '127.0.0.1', port: 19001, token: A.token, deviceId: A.deviceId, quiet: true,
    handlers: { onMessage: (m) => { if (m.ackType === 'STORE') console.log('[A ack-store] seq', m.seq); } },
  });
  clientA.connect();
  await sleep(1200);

  for (let i = 1; i <= count; i++) {
    clientA.sendMessage({ receiverId: B.userId, content: 'fault-msg-' + i, msgType: 'TEXT', clientTime: Date.now() });
    await sleep(600);
  }
  await sleep(3000);
  console.log('SENT', count);
  process.exit(0);
}
main().catch((e) => { console.error(e); process.exit(1); });
