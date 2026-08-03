/**
 * 离线消息 + 增量拉取验证:
 * 1. B 先连上,A 发 2 条 → B 实时收到(在线推送)
 * 2. B 断开(模拟离线)
 * 3. A 再发 3 条 → B 离线,消息落库但不推送
 * 4. B 重连 → 自动增量拉取,补回离线期间 3 条
 *
 * 用法: node verify-offline.js
 */
const { ImClient } = require('./client-core');

const received = [];
let phase = 'online';
let B;

function log(...args) { console.log(`[offline]`, ...args); }

// A:发送方
const clientA = new ImClient({
  host: '127.0.0.1', port: 9999,
  token: 'test-token-123', deviceId: 'D-A', deviceType: 'desktop',
  handlers: {
    onConnected: () => {
      log('A 已连接');
      // B 也连上
      B = new ImClient({
        host: '127.0.0.1', port: 9999,
        token: 'test-token-456', deviceId: 'D-B', deviceType: 'desktop',
        handlers: {
          onConnected: () => {
            log('B 已连接(在线阶段)');
            // 阶段1:B 在线,A 发 2 条
            setTimeout(() => {
              clientA.sendMessage({ receiverId: 'user-B', msgType: 'TEXT', content: '在线-1', clientTime: Date.now() });
              clientA.sendMessage({ receiverId: 'user-B', msgType: 'TEXT', content: '在线-2', clientTime: Date.now() });
              log('A 已发 2 条(在线)');
              // 阶段2:2s 后 B 断开(模拟离线)
              setTimeout(() => {
                log('B 断开(模拟离线)...');
                B.close();
                phase = 'offline';
                // 阶段3:A 离线期间发 3 条
                setTimeout(() => {
                  clientA.sendMessage({ receiverId: 'user-B', msgType: 'TEXT', content: '离线-1', clientTime: Date.now() });
                  clientA.sendMessage({ receiverId: 'user-B', msgType: 'TEXT', content: '离线-2', clientTime: Date.now() });
                  clientA.sendMessage({ receiverId: 'user-B', msgType: 'TEXT', content: '离线-3', clientTime: Date.now() });
                  log('A 已发 3 条(离线期间)');
                  // 阶段4:2s 后 B 重连,应增量拉取补回
                  setTimeout(() => {
                    log('B 重连(应增量拉取)...');
                    reconnectB();
                  }, 2000);
                }, 1000);
              }, 2000);
            }, 500);
          },
          onMessage: (m) => {
            received.push(m);
            log(`B 收到: "${m.content}" seq=${m.seq} [${phase}]`);
            checkDone();
          },
          onClosed: () => log('B 连接关闭'),
        },
      });
      B.connect();
    },
  },
});

let reconnected = false;
function reconnectB() {
  if (reconnected) return;
  reconnected = true;
  const newB = new ImClient({
    host: '127.0.0.1', port: 9999,
    token: 'test-token-456', deviceId: 'D-B', deviceType: 'desktop',
    handlers: {
      onConnected: () => {
        log('B 重连成功,主动增量拉取...');
        // 模拟客户端重启后:本地持久化了 lastSeq=2(在线阶段收到的),补拉 seq>2 的
        newB.conversationLastSeq.set('user-A#user-B', 2);
        newB._pullOffline();
      },
      onMessage: (m) => {
        received.push(m);
        log(`B(重连)收到: "${m.content}" seq=${m.seq} [${phase}]`);
        checkDone();
      },
    },
  });
  newB.connect();
}

let done = false;
function checkDone() {
  if (done) return;
  // 期望收到 5 条:2 在线 + 3 离线补拉
  const contents = received.map(m => m.content);
  const all5 = ['在线-1','在线-2','离线-1','离线-2','离线-3'].every(c => contents.includes(c));
  if (all5) {
    done = true;
    console.log('\n=== 离线消息 + 增量拉取验证 ===');
    received.sort((a, b) => a.seq - b.seq).forEach(m => {
      console.log(`  seq=${m.seq} ${m.content}`);
    });
    const seqs = received.map(m => m.seq);
    const increasing = seqs.every((s, i) => i === 0 || s > seqs[i - 1]);
    console.log(increasing ? '✅ 5 条消息全部收到,且 seq 严格递增' : '❌ seq 未递增');
    process.exit(0);
  }
}

clientA.connect();
setTimeout(() => {
  if (!done) {
    console.error(`\n超时: 只收到 ${received.length} 条`);
    received.sort((a,b) => a.seq-b.seq).forEach(m => console.log(`  seq=${m.seq} ${m.content}`));
    process.exit(1);
  }
}, 30000);
