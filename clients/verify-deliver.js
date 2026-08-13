/**
 * DELIVER 回执验证(双 ACK 第二跳):
 * A 发消息 → B 收到 → B 自动回 DELIVER_ACK → 服务端回 DELIVER 给 A
 * A 看到"已送达"(DELIVER 回执)
 *
 * 用法: node verify-deliver.js
 */
const { ImClient } = require('./client-core');
const { newUser } = require('./test-lib');

(async () => {
  const a = await newUser('dlvA');
  const b = await newUser('dlvB');
  console.log(`A=${a.userId} B=${b.userId}`);

  let storeReceived = false;
  let deliverReceived = false;

  const clientA = new ImClient({
    host: '127.0.0.1', port: 19001,
    token: a.token, deviceId: a.deviceId, deviceType: 'desktop',
    handlers: {
      onConnected: () => {
        console.log('[A] 已连接,等待 B 也连上...');
        // 等 B 连上后再发(否则 B 离线,消息收不到,不会回 DELIVER)
      },
      onAck: (ack) => {
        if (ack.ackType === 'STORE') {
          storeReceived = true;
          console.log(`[A] 收到 STORE(已存储): serverMsgId=${ack.serverMsgId} seq=${ack.seq}`);
          check();
        }
      },
      onDelivered: (ack) => {
        deliverReceived = true;
        console.log(`[A] ★收到 DELIVER(对方已送达): serverMsgId=${ack.serverMsgId} seq=${ack.seq}`);
        check();
      },
    },
  });

  let bReady = false;
  const clientB = new ImClient({
    host: '127.0.0.1', port: 19001,
    token: b.token, deviceId: b.deviceId, deviceType: 'desktop',
    handlers: {
      onConnected: () => {
        console.log('[B] 已连接');
        if (!bReady) {
          bReady = true;
          // B 就绪后,A 发消息
          console.log('[A] B 已就绪,发消息');
          clientA.sendMessage({
            receiverId: b.userId, msgType: 'TEXT',
            content: 'deliver test',
            clientTime: Date.now(),
          });
        }
      },
      onMessage: (m) => {
        console.log(`[B] 收到消息: "${m.content}" seq=${m.seq},自动回 DELIVER_ACK`);
      },
    },
  });

  clientA.connect();
  setTimeout(() => clientB.connect(), 300);

  function check() {
    if (storeReceived && deliverReceived) {
      console.log('\n=== DELIVER 回执验证 ===');
      console.log('✅ A 收到 STORE(已存储) + DELIVER(对方已送达) —— 双 ACK 闭环');
      process.exit(0);
    }
  }

  setTimeout(() => {
    console.error(`\n超时: storeReceived=${storeReceived} deliverReceived=${deliverReceived}`);
    process.exit(1);
  }, 15000);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
