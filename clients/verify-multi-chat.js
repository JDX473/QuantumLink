/** 双 chat 实例验证:同会话消息有序 + 雪花 id 唯一 */
const { ImClient } = require('E:/QIUZHAO/IM/clients/client-core');
const API = 'http://127.0.0.1:8081';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function login(u, p) {
  let r = await fetch(API + '/api/auth/login', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:p,deviceType:'desktop'}) });
  let d = await r.json();
  if (!d.success) {
    await fetch(API + '/api/auth/register', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:p}) });
    d = await (await fetch(API + '/api/auth/login', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:p,deviceType:'desktop'}) })).json();
  }
  return d;
}

(async () => {
  const a = await login('mux_a', 'pass123');
  const b = await login('mux_b', 'pass123');
  console.log('A=' + a.userId + ' B=' + b.userId);

  const acks = [];
  const ca = new ImClient({ host:'127.0.0.1', port:19001, token:a.token, deviceId:a.deviceId, deviceType:'desktop', quiet:true,
    handlers: {
      onConnected: () => console.log('[A] 已连 19001'),
      onAck: (ack) => {
        if (ack.ackType === 'STORE') {
          acks.push({ seq: ack.seq, id: ack.serverMsgId });
          if (acks.length === 10) {
            const seqs = acks.map(x => x.seq);
            const ids = acks.map(x => x.id);
            const seqOk = seqs.every((s, i) => i === 0 || s > seqs[i - 1]);
            const idUniq = new Set(ids).size === ids.length;
            console.log('seq 序列:', seqs.join(','));
            console.log('雪花 id 唯一:', idUniq ? '✅' : '❌');
            console.log(seqOk ? '✅ 双实例下同会话 seq 严格递增(有序未破坏)' : '❌ 乱序!');
            process.exit(0);
          }
        }
      },
    },
  });
  const cb = new ImClient({ host:'127.0.0.1', port:19002, token:b.token, deviceId:b.deviceId, deviceType:'desktop', quiet:true,
    handlers: { onConnected: () => {}, onMessage: () => {} } });
  ca.connect();
  await sleep(800);
  cb.connect();
  await sleep(1500);
  console.log('[A] 连发 10 条...');
  for (let i = 1; i <= 10; i++) ca.sendMessage({ receiverId: b.userId, msgType:'TEXT', content:'mux-' + i, clientTime: Date.now() });
  await sleep(8000);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
