// 群已读端到端验证:
// A 发 3 条群消息 → A 进群上报已读(计数=1)→ B 进群上报(计数=2)→ A 再进群(不重复,仍=2)
const { ImClient } = require('E:/QIUZHAO/IM/clients/client-core.js');
const API = 'http://127.0.0.1:8081';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function post(p, b, token) { const r = await fetch(API + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { 'Authorization': 'Bearer ' + token } : {}) }, body: JSON.stringify(b) }); return r.json(); }
async function get(p, t) { const r = await fetch(API + p, { headers: { 'Authorization': 'Bearer ' + t } }); return r.json(); }
function connect(a, handlers) { return new Promise(async (res, rej) => {
  try {
    const d = await (await fetch(API + '/api/connects', { headers: { 'Authorization': 'Bearer ' + a.token } })).json();
    const [h, p] = d.address.split(':');
    const c = new ImClient({ host: h, port: +p, token: a.token, deviceId: a.deviceId, deviceType: 'desktop', apiBase: API, quiet: true,
      handlers: Object.assign({ onConnected: () => res(c), onClosed: () => {}, onAck: () => {}, onDelivered: () => {}, onRead: () => {}, onMessage: () => {}, onSendFailed: () => {}, onError: () => {} }, handlers || {}) });
    c.connect(); setTimeout(() => rej(new Error('t')), 10000);
  } catch (e) { rej(e); }
});}
(async () => {
  const s = Date.now() % 100000;
  for (const u of ['grA' + s, 'grB' + s]) await post('/api/auth/register', { username: u, password: 'pass123' });
  const [a, b] = await Promise.all(['grA' + s, 'grB' + s].map(u => post('/api/auth/login', { username: u, password: 'pass123', deviceType: 'desktop' })));
  const g = await post('/api/groups', { name: '已读群', members: [b.userId] }, a.token);
  const gid = g.groupId;
  console.log('群:', gid);
  const ca = await connect(a, {});
  const cb = await connect(b, {});
  // A 发 3 条群消息
  for (let i = 1; i <= 3; i++) ca.sendMessage({ receiverId: gid, conversationId: gid, content: '群消息' + i, msgType: 'TEXT', clientTime: Date.now() });
  await sleep(5000);

  // ① A 进群(拉取 + 上报已读到 seq=3)
  ca.reportRead(gid, 3);
  await sleep(2000);
  let page = await get(`/api/groups/${gid}/messages?afterSeq=0&limit=10`, a.token);
  const cnts1 = (page.messages || []).map(m => m.readCount);
  console.log('① A 进群后 readCount:', cnts1.join(','), cnts1.every(c => c === 1) ? '✅ (3条各=1,只有A)' : '❌');

  // ② B 进群(上报已读到 seq=3)→ 计数变 2
  cb.reportRead(gid, 3);
  await sleep(2000);
  page = await get(`/api/groups/${gid}/messages?afterSeq=0&limit=10`, a.token);
  const cnts2 = (page.messages || []).map(m => m.readCount);
  console.log('② B 进群后 readCount:', cnts2.join(','), cnts2.every(c => c === 2) ? '✅ (3条各=2,A+B)' : '❌');

  // ③ A 再次进群(重复上报同水位)→ 不应重复计数,仍=2
  ca.reportRead(gid, 3);
  await sleep(2000);
  page = await get(`/api/groups/${gid}/messages?afterSeq=0&limit=10`, a.token);
  const cnts3 = (page.messages || []).map(m => m.readCount);
  console.log('③ A 再进群 readCount:', cnts3.join(','), cnts3.every(c => c === 2) ? '✅ (不重复计数)' : '❌');

  ca.close(); cb.close();
  const pass = cnts1.every(c=>c===1) && cnts2.every(c=>c===2) && cnts3.every(c=>c===2);
  console.log(pass ? '\n=== 群已读端到端 PASS ===' : '\n=== 有 FAIL ===');
  process.exit(pass ? 0 : 1);
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
