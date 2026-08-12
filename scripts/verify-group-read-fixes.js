// 两个 bug 修复验证:
// 1. 面对面建群后群列表能查到(之前 groupId 不一致)
// 2. 群已读:A发消息(发送者自动计入水位)→ B读到 → A实时收到 GROUP_READ 推送(readCount)
const { ImClient } = require('../clients/client-core.js');
const API = process.env.IM_API || 'http://127.0.0.1:8081';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function post(p, b, token) { const r = await fetch(API + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { 'Authorization': 'Bearer ' + token } : {}) }, body: JSON.stringify(b) }); return r.json(); }
async function get(p, t) { const r = await fetch(API + p, { headers: { 'Authorization': 'Bearer ' + t } }); return r.json(); }
function connect(a, h) { return new Promise(async (res, rej) => { try {
  const d = await (await fetch(API+'/api/connects',{headers:{'Authorization':'Bearer '+a.token}})).json();
  const [hp,p] = d.address.split(':');
  const c = new ImClient({host:hp,port:+p,token:a.token,deviceId:a.deviceId,deviceType:'desktop',apiBase:API,quiet:true,
    handlers:Object.assign({onConnected:()=>res(c),onClosed:()=>{},onAck:()=>{},onDelivered:()=>{},onRead:()=>{},onGroupRead:()=>{},onMessage:()=>{},onSendFailed:()=>{},onError:()=>{}},h||{})});
  c.connect(); setTimeout(()=>rej(new Error('t')),8000);
}catch(e){rej(e);} });}
(async () => {
  const s = Date.now()%100000;
  for (const u of ['fxA'+s,'fxB'+s]) await post('/api/auth/register',{username:u,password:'pass123'});
  const [a,b] = await Promise.all(['fxA'+s,'fxB'+s].map(u=>post('/api/auth/login',{username:u,password:'pass123',deviceType:'desktop'})));

  // ===== 1. 面对面建群 → 群列表 =====
  console.log('=== 修复1:面对面建群后群列表 ===');
  const code = String(Math.floor(1000 + Math.random()*9000));
  const f2f = await post('/api/groups/face2face', { code }, a.token);
  const listA = await get('/api/groups', a.token);
  const inList = (listA.groups||[]).some(g => g.groupId === f2f.groupId);
  console.log('建群返回 groupId:', f2f.groupId, '| 群列表能查到:', inList ? '✅ 修复' : '❌ 仍缺失');
  const listB = await get('/api/groups', b.token);
  const binList = (listB.groups||[]).some(g => g.groupId === f2f.groupId);
  console.log('B 也加入后(未加入),B 列表不应有:', binList ? '❌' : '✅(符合预期)');

  // ===== 2. 群已读:发送者实时收到已读数 =====
  console.log('\n=== 修复2:发送者实时收到已读数 ===');
  const g = await post('/api/groups', { name: '实时群', members: [b.userId] }, a.token);
  const gid = g.groupId;
  let aGroupReads = [];
  const ca = await connect(a, { onGroupRead: (r) => { aGroupReads.push(r); console.log('[A] 收到 GROUP_READ:', JSON.stringify(r)); } });
  const cb = await connect(b, { onMessage: (m) => { console.log('[B] 收到群消息 seq='+m.seq+' → 上报已读'); cb.reportRead(gid, m.seq); } });
  await sleep(1500);
  ca.reportRead(gid, 0); // A 打开群
  await sleep(500);
  ca.sendMessage({ receiverId: gid, conversationId: gid, content: '实时已读测试', msgType: 'TEXT', clientTime: Date.now() });
  await sleep(4000);
  // A 应收到 GROUP_READ 推送(seq=1, readCount=2 = A自己 + B)
  const gotPush = aGroupReads.some(r => r.seq === 1);
  const pushCount = aGroupReads.find(r => r.seq === 1);
  console.log('A 收到实时已读推送:', gotPush ? '✅' : '❌', '| readCount =', pushCount ? pushCount.readCount : '?', '| A界面应显示 count-1 =', pushCount ? (pushCount.readCount - 1) : '?');
  // 拉取接口确认 readCount
  const page = await get('/api/groups/'+gid+'/messages?afterSeq=0&limit=10', a.token);
  const m = (page.messages||[])[0];
  console.log('拉取接口 readCount =', m ? m.readCount : '?', '(应为2:A自动计入+B)');
  ca.close(); cb.close();
  const pass = inList && gotPush && m && m.readCount === 2;
  console.log(pass ? '\n=== 两个修复验证 PASS ===' : '\n=== 有 FAIL ===');
  process.exit(pass?0:1);
})().catch(e=>{console.error('ERR',e.message);process.exit(1);});
