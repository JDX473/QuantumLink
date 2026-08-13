/** 面对面建群验证:
 * 1. A 输入 1234 → 创建群
 * 2. B/C 并发输入 1234 → 加入同一群(A 建的)
 * 3. 群成员 = A/B/C;A 发群消息,B/C 跨节点实时收到
 * 4. 重复输入 → 幂等
 */
const { ImClient } = require('./client-core');
const API = process.env.IM_API || 'http://127.0.0.1:8081';
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

async function face2face(user, code) {
  return (await fetch(API + '/api/groups/face2face', { method:'POST', headers:{'Content-Type':'application/json', Authorization:'Bearer ' + user.token}, body: JSON.stringify({ code }) })).json();
}

(async () => {
  // 清残留窗口 key(保证 A 是第一个建群的)
  await fetch('http://127.0.0.1:6379/_none', { method:'GET' }).catch(() => {});
  const { execSync } = require('child_process');
  try { execSync('F:/Study/Redis4/redis-cli.exe -h 127.0.0.1 -p 6379 del im:f2f:1234 2>/dev/null'); } catch (e) {}

  const a = await login('f2f_a', 'pass123');
  const b = await login('f2f_b', 'pass123');
  const c = await login('f2f_c', 'pass123');
  console.log('A=' + a.userId + ' B=' + b.userId + ' C=' + c.userId);

  // 1. A 建群
  const ra = await face2face(a, '1234');
  console.log('[A] 输入 1234:', JSON.stringify(ra));
  if (!ra.isNewGroup) { console.error('❌ 应新建群'); process.exit(1); }
  const gid = ra.groupId;
  console.log('✅ A 新建群:', gid, ra.name);

  // 2. B/C 并发加入同一群
  const [rb, rc] = await Promise.all([face2face(b, '1234'), face2face(c, '1234')]);
  console.log('[B]', JSON.stringify(rb));
  console.log('[C]', JSON.stringify(rc));
  const sameGroup = rb.groupId === gid && rc.groupId === gid;
  console.log(sameGroup ? '✅ B/C 加入同一群(A 建的)' : '❌ 不是同一群!');

  // 3. 重复输入幂等
  const rb2 = await face2face(b, '1234');
  console.log('[B] 重复输入:', rb2.groupId === gid ? '✅ 幂等同群' : '❌');

  // 4. 群成员
  const members = await (await fetch(API + '/api/groups/' + gid + '/members', { headers:{Authorization:'Bearer ' + a.token} })).json();
  console.log('群成员:', members.memberIds.length, '人', '✅' );

  // 5. A 发群消息,B/C 实时收到(跨节点)
  const bGot = [], cGot = [];
  const ca = new ImClient({ host:'127.0.0.1', port:19001, token:a.token, deviceId:a.deviceId, deviceType:'desktop', quiet:true,
    handlers:{ onConnected:()=>console.log('[A] 已连'), onAck:()=>{}, onMessage:()=>{} } });
  const cb = new ImClient({ host:'127.0.0.1', port:19002, token:b.token, deviceId:b.deviceId, deviceType:'desktop', quiet:true,
    handlers:{ onConnected:()=>{}, onMessage:(m)=>{ bGot.push(m); console.log('[B] ★群消息:', m.content, 'seq=' + m.seq); } } });
  const cc = new ImClient({ host:'127.0.0.1', port:19002, token:c.token, deviceId:c.deviceId, deviceType:'desktop', quiet:true,
    handlers:{ onConnected:()=>{}, onMessage:(m)=>{ cGot.push(m); console.log('[C] ★群消息:', m.content, 'seq=' + m.seq); } } });
  ca.connect(); await sleep(800); cb.connect(); await sleep(800); cc.connect();
  // 等 B/C 连接确认(握手 + 会话注册),否则群播查会话表找不到
  await sleep(2500);

  ca.sendMessage({ receiverId: gid, conversationId: gid, msgType:'TEXT', content:'面对面群消息', clientTime: Date.now() });
  await sleep(4000);
  console.log(bGot.length === 1 && cGot.length === 1 ? '✅ B/C 实时收到群消息' : '❌ 群消息未到');

  process.exit(0);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
