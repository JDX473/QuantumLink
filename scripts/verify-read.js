// 单聊已读端到端验证:
// A 发消息 → B 收到 → B 上报已读 → A 收到 READ 事件 → 校验 Redis 水位 + 拉历史 peerReadSeq
const { ImClient } = require('E:/QIUZHAO/IM/clients/client-core.js');
const API = 'http://127.0.0.1:8081';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function post(path, body) {
  const res = await fetch(API + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  return res.json();
}
async function get(path, token) {
  const res = await fetch(API + path, { headers: { 'Authorization': 'Bearer ' + token } });
  return res.json();
}

function connectUser(auth, handlers) {
  return new Promise(async (resolve, reject) => {
    try {
      const dispatchRes = await fetch(API + '/api/connects', { headers: { 'Authorization': 'Bearer ' + auth.token } });
      const dispatch = await dispatchRes.json();
      const [host, port] = dispatch.address.split(':');
      const client = new ImClient({
        host, port: parseInt(port), token: auth.token, deviceId: auth.deviceId,
        deviceType: 'desktop', apiBase: API,
        handlers: Object.assign({
          onConnected: () => resolve(client),
          onClosed: () => {}, onAck: () => {}, onDelivered: () => {},
          onRead: () => {}, onMessage: () => {}, onSendFailed: () => {}, onError: () => {},
        }, handlers || {}),
      });
      client.connect();
      setTimeout(() => reject(new Error('connect timeout')), 10000);
    } catch (e) { reject(e); }
  });
}

(async () => {
  const suffix = Date.now() % 100000;
  const unameA = 'readA' + suffix, unameB = 'readB' + suffix;
  await post('/api/auth/register', { username: unameA, password: 'pass123' });
  await post('/api/auth/register', { username: unameB, password: 'pass123' });
  const a = await post('/api/auth/login', { username: unameA, password: 'pass123', deviceType: 'desktop' });
  const b = await post('/api/auth/login', { username: unameB, password: 'pass123', deviceType: 'desktop' });
  console.log('A userId:', a.userId, '| B userId:', b.userId);
  const convId = [a.userId, b.userId].sort().join('#');
  console.log('convId:', convId);

  const bMessages = [];
  const aReads = [];
  const clientA = await connectUser(a, { onRead: (r) => { aReads.push(r); console.log('[A] 收到 READ 事件:', JSON.stringify(r)); } });
  const clientB = await connectUser(b, { onMessage: (m) => { bMessages.push(m); console.log('[B] 收到消息 seq=' + m.seq + ' content=' + m.content); } });
  console.log('=== 两端已连接 ===');

  clientA.sendMessage({ receiverId: b.userId, content: '已读测试消息', msgType: 'TEXT', clientTime: Date.now() });
  await sleep(6000);
  if (bMessages.length === 0) { console.log('FAIL: B 未收到消息'); process.exit(1); }
  const msg = bMessages[0];
  console.log('=== B 上报已读: conv=' + convId + ' untilSeq=' + msg.seq + ' ===');
  clientB.reportRead(convId, msg.seq);

  await sleep(6000);
  if (aReads.length === 0) { console.log('FAIL: A 未收到 READ 事件'); process.exit(1); }
  const evt = aReads[0];
  const okEvt = evt.conversationId === convId && evt.readerId === b.userId && evt.untilSeq >= msg.seq;
  console.log('READ 事件校验:', okEvt ? 'PASS' : 'FAIL', '(reader=' + evt.readerId + ' untilSeq=' + evt.untilSeq + ')');

  // A 拉历史:应带 peerReadSeq
  const pull = await get(`/api/conversations/${encodeURIComponent(convId)}/messages?afterSeq=0&limit=50`, a.token);
  const peerRead = pull.peerReadSeq || 0;
  console.log('拉历史 peerReadSeq =', peerRead, '| 期望 >=', msg.seq, '→', peerRead >= msg.seq ? 'PASS' : 'FAIL');
  const myMsg = (pull.messages || []).find(m => m.senderId === a.userId);
  console.log('自己消息 seq =', myMsg ? myMsg.seq : '?', '| 渲染应为: seq<=peerRead → 已读 →', (myMsg && myMsg.seq <= peerRead) ? '对方已读' : '未读');

  clientA.close(); clientB.close();
  console.log(okEvt && peerRead >= msg.seq ? '\n=== 端到端全部 PASS ===' : '\n=== 有 FAIL ===');
  process.exit(0);
})().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
