// UUID clientMsgId 深度自测(临时,不入库):
// 1. 快速连发 20 条 → 全部 ACK 匹配、pending 清空、B 收到 20 条、DB 恰好 20 条(无重复)
// 2. 同 UUID 手动重传 → 服务端返回相同 seq,DB 不新增
// 3. 断线后发送(排队)→ 重连 flush → 用同一 UUID 重发 → 不重复落库
const { ImClient } = require('E:/QIUZHAO/IM/clients/client-core.js');
const { FrameType } = require('E:/QIUZHAO/IM/clients/protocol.js');
const API = 'http://127.0.0.1:8081';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function post(p, b) { return (await (await fetch(API+p,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)})).json()); }
function connect(a, handlers) { return new Promise(async (res, rej) => {
  try {
    const d = await (await fetch(API+'/api/connects',{headers:{'Authorization':'Bearer '+a.token}})).json();
    const [h,p] = d.address.split(':');
    const c = new ImClient({host:h, port:+p, token:a.token, deviceId:a.deviceId, deviceType:'desktop', apiBase:API, quiet:true,
      handlers:Object.assign({onConnected:()=>res(c), onClosed:()=>{},onAck:()=>{},onDelivered:()=>{},onRead:()=>{},onMessage:()=>{},onSendFailed:()=>{},onError:()=>{}}, handlers||{})});
    c.connect(); setTimeout(()=>rej(new Error('connect timeout')), 10000);
  } catch(e){ rej(e); }
});}
async function countMessages(convId, token) {
  const r = await (await fetch(API+'/api/conversations/'+encodeURIComponent(convId)+'/messages?afterSeq=0&limit=200',{headers:{'Authorization':'Bearer '+token}})).json();
  return (r.messages||[]).length;
}
(async () => {
  const s = Date.now()%100000;
  for (const u of ['selA'+s,'selB'+s]) await post('/api/auth/register',{username:u,password:'pass123'});
  const [a,b] = await Promise.all(['selA'+s,'selB'+s].map(u=>post('/api/auth/login',{username:u,password:'pass123',deviceType:'desktop'})));
  const convId = [a.userId,b.userId].sort().join('#');
  let ackMap = new Map(); // clientMsgId -> ack
  let bGot = [];
  const ca = await connect(a, { onAck:(ack)=>{ if(ack.ackType==='STORE') ackMap.set(ack.clientMsgId, ack); } });
  const cb = await connect(b, { onMessage:(m)=>bGot.push(m) });
  console.log('=== 1. 快速连发 20 条 ===');
  const sentIds = [];
  for (let i=0;i<20;i++) sentIds.push(ca.sendMessage({receiverId:b.userId, conversationId:convId, content:'m'+i, msgType:'TEXT', clientTime:Date.now()}));
  await sleep(6000);
  const acked = sentIds.filter(id=>ackMap.has(id));
  const allUuid = sentIds.every(id=>/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id));
  const pendingEmpty = ca.pending.size === 0;
  const uniqIds = new Set(sentIds).size === sentIds.length;
  console.log('20 条全 UUID:', allUuid ? '✅' : '❌');
  console.log('20 条 UUID 互不重复:', uniqIds ? '✅' : '❌');
  console.log('ACK 全部匹配(20/'+acked.length+'):', acked.length===20 ? '✅' : '❌');
  console.log('pending 已清空:', pendingEmpty ? '✅' : '❌');
  console.log('B 收到条数:', bGot.length, bGot.length===20 ? '✅' : '❌');
  const bGotStep1 = bGot.length; // 记录 step1 时的条数(step3 还会合法收到 1 条)
  const cnt1 = await countMessages(convId, a.token);
  console.log('DB 消息数(应20):', cnt1, cnt1===20 ? '✅' : '❌');

  console.log('=== 2. 同 UUID 手动重传 → 不重复落库 ===');
  const dupId = sentIds[0];
  const beforeDup = ackMap.get(dupId);
  ca.sendFrame(FrameType.MSG, { receiverId:b.userId, conversationId:convId, content:'重传', msgType:'TEXT', clientMsgId:dupId });
  await sleep(3000);
  const afterDup = ackMap.get(dupId); // 服务端回带相同 ack
  const sameSeq = beforeDup.seq === afterDup.seq && beforeDup.serverMsgId === afterDup.serverMsgId;
  const cnt2 = await countMessages(convId, a.token);
  console.log('重传返回相同 seq+serverMsgId:', sameSeq ? '✅' : '❌');
  console.log('DB 仍是 20(未新增):', cnt2===20 ? '✅' : '❌');

  console.log('=== 3. 断线后发送(排队)→ 重连 flush → 同一 UUID 重发 ===');
  ca.socket.destroy(); // 强制断线
  await sleep(500);
  const queuedId = ca.sendMessage({receiverId:b.userId, conversationId:convId, content:'断线期间发的', msgType:'TEXT', clientTime:Date.now()});
  console.log('断线时发送,立即进 pending:', ca.pending.has(queuedId) ? '✅' : '❌', '| 是 UUID:', /^[0-9a-f]{8}-[0-9a-f]{4}-4/.test(queuedId) ? '✅' : '❌');
  await sleep(6000); // 等重连 + flush + ACK
  const queuedAcked = ackMap.has(queuedId);
  const cnt3 = await countMessages(convId, a.token);
  console.log('重连后 flush 发送并收到 ACK:', queuedAcked ? '✅' : '❌');
  console.log('DB 消息数(应21):', cnt3, cnt3===21 ? '✅' : '❌');
  console.log('断线期间消息只落库 1 次(不重复):', cnt3===21 ? '✅' : '❌');

  ca.close(); cb.close();
  const pass = allUuid && uniqIds && acked.length===20 && pendingEmpty && bGotStep1===20 && cnt1===20 && sameSeq && cnt2===20 && queuedAcked && cnt3===21;
  console.log('\n' + (pass ? '=== 深度自测全部 PASS ===' : '=== 有 FAIL ==='));
  process.exit(pass?0:1);
})().catch(e=>{console.error('ERR',e.message);process.exit(1);});
