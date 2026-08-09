// 多端踢人验证:
// 1. 同端类型踢人:U 在 mobile 设备A 登录并连接 → 再在 mobile 设备B 登录 → A 被踢(连接断开)
// 2. 设备踢除端点:POST /api/auth/devices/{deviceId}/kick 踢掉指定设备
const { ImClient } = require('E:/QIUZHAO/IM/clients/client-core.js');
const API = 'http://127.0.0.1:8081';
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
  await post('/api/auth/register',{username:'kk'+s,password:'pass123'});
  // U 在 mobile 设备A 登录
  const uA = await post('/api/auth/login',{username:'kk'+s,password:'pass123',deviceType:'mobile',deviceId:'d_mobA'});
  console.log('U 设备A(mobile):', uA.deviceId);
  let aClosed = 0;
  const ca = await connect(uA, { onClosed: () => { aClosed++; console.log('[A] 连接被断开(onClosed)'); } });
  await sleep(1000);
  console.log('① A 已连接');

  // 同端类型(mobile)新登录 → 踢 A
  console.log('\n=== 同端踢人:mobile 设备B 登录 → 踢 mobile 设备A ===');
  const uB = await post('/api/auth/login',{username:'kk'+s,password:'pass123',deviceType:'mobile',deviceId:'d_mobB'});
  let bClosed = 0;
  const cb = await connect(uB, { onClosed: () => { bClosed++; console.log('[B] 连接被断开(onClosed)'); } });
  console.log('B 登录并连接:', uB.deviceId);
  await sleep(3000);
  console.log('② A 被踢(onClosed 触发):', aClosed >= 1 ? '✅' : '❌', '| A 断开次数:', aClosed);

  // 设备列表:只有 B 在线,A 离线(用 uB token)
  const devs = await get('/api/auth/devices', uB.token);
  const list = (devs.devices||[]).map(d => d.deviceId + ':' + (d.online?'在线':'离线'));
  console.log('③ 设备列表:', list.join(' | '));
  const bOn = (devs.devices||[]).find(d=>d.deviceId==='d_mobB');
  const aOff = (devs.devices||[]).find(d=>d.deviceId==='d_mobA');
  console.log('   B在线:', bOn && bOn.online ? '✅' : '❌', '| A离线:', aOff && !aOff.online ? '✅' : '❌');

  // 设备踢除端点:踢 B(B 的 token 会被删 → 连接断开)
  console.log('\n=== 设备踢除端点:踢 B ===');
  const kickRes = await post('/api/auth/devices/d_mobB/kick', {}, uB.token);
  console.log('KICK 接口返回:', kickRes.success ? '✅' : '❌');
  await sleep(3000);
  console.log('④ B 连接被踢(onClosed):', bClosed >= 1 ? '✅' : '❌', '| B 断开次数:', bClosed);
  // B 的 token 已删,需重新登录拿有效 token 查设备列表(不同 desktop 类型不被踢)
  const uDesk = await post('/api/auth/login',{username:'kk'+s,password:'pass123',deviceType:'desktop',deviceId:'d_desk'});
  const devs2 = await get('/api/auth/devices', uDesk.token);
  const bOff = (devs2.devices||[]).find(d=>d.deviceId==='d_mobB');
  console.log('⑤ 踢除后 B 在线状态:', bOff && bOff.online ? '❌ 还在线' : '✅ 已离线');

  ca.close(); cb.close();
  const pass = aClosed >= 1 && bOn && bOn.online && aOff && !aOff.online && bClosed >= 1 && bOff && !bOff.online;
  console.log(pass ? '\n=== 多端踢人验证 PASS ===' : '\n=== 有 FAIL ===');
  process.exit(pass?0:1);
})().catch(e=>{console.error('ERR',e.message);process.exit(1);});
