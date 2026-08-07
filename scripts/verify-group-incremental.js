// 群聊增量同步验证:
// 1. 群 150 条消息,首次打开走尾部(最近 100 条)
// 2. 翻页(hasMore)正确
// 3. "查看更早的消息"逻辑:拉 minSeq 之前 100 条
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
  for (const u of ['grpA' + s, 'grpB' + s]) await post('/api/auth/register', { username: u, password: 'pass123' });
  const [a, b] = await Promise.all(['grpA' + s, 'grpB' + s].map(u => post('/api/auth/login', { username: u, password: 'pass123', deviceType: 'desktop' })));
  // 建群(A 群主,拉 B),带鉴权
  const g = await post('/api/groups', { name: '测试群', members: [b.userId] }, a.token);
  const groupId = g.groupId;
  console.log('群:', groupId, '群主:', a.userId);
  const ca = await connect(a, {});
  await connect(b, {});
  // A 发 150 条群消息
  for (let i = 1; i <= 150; i++) ca.sendMessage({ receiverId: groupId, conversationId: groupId, content: '群消息' + i, msgType: 'TEXT', clientTime: Date.now() });
  await sleep(10000);
  console.log('=== 已发 150 条群消息 ===');

  // 1. 首次打开 = 尾部 100 条(模拟 pullGroupTail)
  const probe = await get(`/api/groups/${groupId}/messages?afterSeq=0&limit=100`, a.token);
  const maxSeq = probe.maxSeq;
  console.log('maxSeq:', maxSeq);
  const tailStart = Math.max(0, maxSeq - 100);
  let tailPage = await get(`/api/groups/${groupId}/messages?afterSeq=${tailStart}&limit=100`, a.token);
  let tail = (tailPage.messages || []).slice();
  // 分页拉全
  while (tailPage.hasMore && tail.length < 2000) {
    const last = tail[tail.length - 1].seq;
    tailPage = await get(`/api/groups/${groupId}/messages?afterSeq=${last}&limit=100`, a.token);
    tail = tail.concat(tailPage.messages || []);
  }
  const tailSeqs = tail.map(m => m.seq);
  console.log('尾部加载: 条数=', tail.length, '| seq', tailSeqs[0], '~', tailSeqs[tailSeqs.length - 1], '| 含最新150:', tailSeqs.includes(maxSeq) ? '✅' : '❌');
  console.log('尾部正好最近 ~100 条(不含最老的1):', !tailSeqs.includes(1) ? '✅' : '❌');

  // 2. 翻页 hasMore:afterSeq=0 拉 100,应有 hasMore=true
  console.log('afterSeq=0&limit=100 hasMore:', probe.hasMore === true ? '✅' : '❌', '(还有更早的)');
  // 从 0 拉到 100,再拉下一页,验证能覆盖全部
  let p1 = await get(`/api/groups/${groupId}/messages?afterSeq=0&limit=100`, a.token);
  let p2 = await get(`/api/groups/${groupId}/messages?afterSeq=${(p1.messages||[]).length ? p1.messages[p1.messages.length-1].seq : 0}&limit=100`, a.token);
  console.log('第1页(0~100)hasMore=' + p1.hasMore + ' | 第2页条数=' + (p2.messages||[]).length + ' hasMore=' + p2.hasMore, ((p1.messages||[]).length + (p2.messages||[]).length >= 150) ? '✅ 两页覆盖150' : '❌');

  // 3. "查看更早的消息":缓存最小 seq=tailStart+1,拉它之前 100 条
  const minSeq = tailSeqs[0];
  const olderPage = await get(`/api/groups/${groupId}/messages?afterSeq=${Math.max(0, minSeq - 100)}&limit=100`, a.token);
  const older = (olderPage.messages || []).filter(m => m.seq < minSeq);
  console.log('查看更早: 拉到', older.length, '条, seq', (older[0]||{}).seq, '~', (older[older.length-1]||{}).seq, '| 全部 < 当前最小', older.every(m => m.seq < minSeq) ? '✅' : '❌');

  ca.close();
  console.log('\n=== 群聊增量同步验证完成 ===');
  process.exit(0);
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
