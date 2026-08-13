// 多端验证:
// 1. 同一账号在两台设备(不同 deviceId)登录 → 设备列表显示两设备都在线
// 2. 第三方发消息给该用户 → 两设备都实时收到(多端全推)
// 3. 用同一持久 deviceId 重新登录 → 设备列表不增长(复用同一台设备)
const { ImClient } = require('../clients/client-core.js');
const API = process.env.IM_API || 'http://127.0.0.1:8081';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function post(p, b, t) { const r = await fetch(API + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(t ? { 'Authorization': 'Bearer ' + t } : {}) }, body: JSON.stringify(b) }); return r.json(); }
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
  // 注册:U(多端用户)、P(第三方发消息给 U)
  for (const u of ['mdU'+s,'mdP'+s]) await post('/api/auth/register',{username:u,password:'pass123'});
  // U 在两台设备登录(持久 deviceId:d_devA / d_devB)
  const uA = await post('/api/auth/login',{username:'mdU'+s,password:'pass123',deviceType:'desktop',deviceId:'d_devA'});
  const uB = await post('/api/auth/login',{username:'mdU'+s,password:'pass123',deviceType:'mobile',deviceId:'d_devB'});
  const p = await post('/api/auth/login',{username:'mdP'+s,password:'pass123',deviceType:'desktop',deviceId:'d_devP'});
  console.log('U userId:', uA.userId, '| 设备A:', uA.deviceId, '| 设备B:', uB.deviceId);

  // 两设备都连上
  let aGot = [], bGot = [];
  const ca = await connect(uA, { onMessage:(m)=>aGot.push(m) });
  const cb = await connect(uB, { onMessage:(m)=>bGot.push(m) });
  const cp = await connect(p, {});
  await sleep(1500);

  // ① 设备列表:两设备都在线
  const devs = await get('/api/auth/devices', uA.token);
  const list = devs.devices || [];
  const devAon = list.find(d=>d.deviceId==='d_devA'), devBon = list.find(d=>d.deviceId==='d_devB');
  console.log('① 设备列表:', list.map(d=>d.deviceId+':'+(d.online?'在线':'离线')).join(' | '));
  console.log('   设备A在线:', devAon && devAon.online ? '✅' : '❌', '| 设备B在线:', devBon && devBon.online ? '✅' : '❌');

  // ② P 给 U 发消息 → 两设备都收到(多端全推)
  const convId = [p.userId, uA.userId].sort().join('#');
  cp.sendMessage({receiverId:uA.userId, conversationId:convId, content:'多端测试', msgType:'TEXT', clientTime:Date.now()});
  await sleep(4000);
  console.log('② P 发消息后:设备A收到', aGot.length, '条, 设备B收到', bGot.length, '条',
    aGot.length>=1 && bGot.length>=1 ? '✅(多端全推)' : '❌');

  // ③ 用持久 deviceId 重新登录 → 设备列表不增长(复用 d_devA)
  await post('/api/auth/login',{username:'mdU'+s,password:'pass123',deviceType:'desktop',deviceId:'d_devA'});
  const devs2 = await get('/api/auth/devices', uA.token);
  const count = (devs2.devices||[]).length;
  console.log('③ 重登后设备总数:', count, count===2 ? '✅(复用 d_devA,不新增)' : '❌');

  ca.close(); cb.close(); cp.close();
  const pass = (devAon && devAon.online) && (devBon && devBon.online) && aGot.length>=1 && bGot.length>=1 && count===2;
  console.log(pass ? '\n=== 多端验证 PASS ===' : '\n=== 有 FAIL ===');
  process.exit(pass?0:1);
})().catch(e=>{console.error('ERR',e.message);process.exit(1);});
