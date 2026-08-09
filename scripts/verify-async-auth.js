// 异步鉴权 + 待鉴权帧队列验证:
// 1. 注册两个用户 A/B
// 2. B 用 client-core 正常连接握手
// 3. A 用 raw TCP 在连上后【立即连发 HANDSHAKE + MSG 两帧】(不等握手 ACK)
//    → MSG 必然在服务端异步鉴权期间到达 → 必须被待鉴权队列接住、鉴权后补处理
// 4. 验证:A 收到 HANDSHAKE_ACK(success) + MSG_ACK(STORE),且 B 收到消息
const net = require('net');
const crypto = require('crypto');
const { FrameType, encode, FrameDecoder } = require('../clients/protocol');
const { ImClient } = require('../clients/client-core');
const API = 'http://127.0.0.1:8081';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function post(p, b) { const r = await fetch(API + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) }); return r.json(); }

(async () => {
  const s = Date.now() % 100000;
  await post('/api/auth/register', { username: 'aa' + s, password: 'pass123' });
  await post('/api/auth/register', { username: 'bb' + s, password: 'pass123' });
  const A = await post('/api/auth/login', { username: 'aa' + s, password: 'pass123', deviceType: 'desktop' });
  const B = await post('/api/auth/login', { username: 'bb' + s, password: 'pass123', deviceType: 'desktop' });
  console.log('A userId:', A.userId, '| B userId:', B.userId);

  // B:client-core 正常连接
  let bGot = false;
  const b = new ImClient({
    host: '127.0.0.1', port: 19001, token: B.token, deviceId: B.deviceId, deviceType: 'desktop', apiBase: API,
    handlers: { onConnected: () => console.log('[B] 握手成功'), onMessage: (m) => { console.log('[B] 收到:', m.content, 'seq=' + m.seq); bGot = true; } },
  });
  b.connect();
  await sleep(500);

  // A:raw TCP,HANDSHAKE 与 MSG 连发(不等握手 ACK)→ 服务器待鉴权队列必须接住 MSG
  const result = await new Promise((resolve) => {
    const dec = new FrameDecoder();
    let gotAck = false, gotStoreAck = false;
    const cmid = crypto.randomUUID();
    const sock = net.connect(19001, '127.0.0.1', () => {
      console.log('[A-raw] TCP 连上,立即连发 HANDSHAKE + MSG(不等待握手 ACK)');
      sock.write(encode(FrameType.HANDSHAKE, { token: A.token, deviceId: A.deviceId }));
      sock.write(encode(FrameType.MSG, { clientMsgId: cmid, receiverId: B.userId, msgType: 'TEXT', content: 'hi ' + s }));
    });
    sock.on('data', (d) => { try { dec.push(d, (f) => {
      if (f.type === FrameType.HANDSHAKE_ACK) { console.log('[A-raw] HANDSHAKE_ACK success=' + f.body.success); gotAck = f.body.success === true; }
      if (f.type === FrameType.MSG_ACK) { console.log('[A-raw] MSG_ACK:', JSON.stringify(f.body)); if (f.body.ackType === 'STORE') gotStoreAck = true; }
    }); } catch (e) { console.error('[A-raw] decode err', e.message); } });
    sock.on('error', (e) => console.error('[A-raw] sock err', e.message));
    setTimeout(() => {
      sock.destroy(); b.close();
      const pass = gotAck && gotStoreAck && bGot;
      console.log(pass ? '\n=== 异步鉴权 + 待鉴权队列 PASS(握手ACK + MSG入队补处理 + 对方收到) ==='
                       : '\n=== FAIL === ack=' + gotAck + ' storeAck=' + gotStoreAck + ' bGot=' + bGot);
      resolve(pass ? 0 : 1);
    }, 6000);
  });
  process.exit(result);
})().catch((e) => { console.error('ERR', e); process.exit(1); });
