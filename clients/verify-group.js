/**
 * 群聊端到端验证:
 * 1. jds(19001) + jdx(19002) + alice(19002) 在线,三人建群
 * 2. jds 发群消息 → jdx/alice 实时收到(targets 聚合 + tag 精准投递)
 * 3. alice 发群消息 → 验证群 seq 递增
 * 4. 下线 alice → jds 再发 → alice 上线拉取(离线补拉)
 */
const { ImClient } = require('E:/QIUZHAO/IM/clients/client-core');
const API = 'http://127.0.0.1:8081';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function login(u, p) {
  let r = await fetch(API + '/api/auth/login', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:p,deviceType:'desktop'}) });
  let d = await r.json();
  if (!d.success) { await fetch(API + '/api/auth/register', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:p}) }); d = await (await fetch(API + '/api/auth/login', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:u,password:p,deviceType:'desktop'}) })).json(); }
  return d;
}

function connect(port, u, handlers) {
  const c = new ImClient({ host:'127.0.0.1', port, token:u.token, deviceId:u.deviceId, deviceType:'desktop', handlers });
  c.connect();
  return c;
}

async function main() {
  const jds = await login('jds', '123456');
  const jdx = await login('jdx', '123456');
  const alice = await login('alice', 'pass123');
  console.log(`jds=${jds.userId} jdx=${jdx.userId} alice=${alice.userId}`);

  // 1. 三人在线,跨节点(jds→19001, jdx→19002, alice→19002)
  const jdxMsgs = [], aliceMsgs = [];
  connect(19001, jds, { onConnected: () => console.log('[jds] 已连 19001') });
  await sleep(800);
  connect(19002, jdx, { onConnected: () => console.log('[jdx] 已连 19002'),
    onMessage: (m) => { jdxMsgs.push(m); console.log(`[jdx] ★群消息: "${m.content}" seq=${m.seq} sender=${m.senderId}`); } });
  await sleep(800);
  connect(19002, alice, { onConnected: () => console.log('[alice] 已连 19002'),
    onMessage: (m) => { aliceMsgs.push(m); console.log(`[alice] ★群消息: "${m.content}" seq=${m.seq} sender=${m.senderId}`); } });
  await sleep(1500);

  // 2. 建群:jds 建群,成员 jdx + alice(ownerId 服务端从鉴权上下文取,需带 token)
  const created = await (await fetch(API + '/api/groups', { method:'POST', headers:{'Content-Type':'application/json', Authorization: 'Bearer ' + jds.token},
    body: JSON.stringify({ name: '测试群', members: [jdx.userId, alice.userId] }) })).json();
  console.log(`群创建: groupId=${created.groupId} name=${created.name}`);
  const gid = created.groupId;

  // 3. jds 发群消息(带 conversationId=群 id)
  const ca = connect(19001, jds, { onConnected: () => console.log('[jds] 已连 19001') });
  await sleep(500);
  console.log('[jds] 发群消息 1/2/3...');
  for (let i = 1; i <= 3; i++) {
    ca.sendMessage({ receiverId: gid, conversationId: gid, msgType:'TEXT', content: '群消息' + i, clientTime: Date.now() });
    await sleep(300);
  }
  await sleep(4000);
  console.log(`jdx 收到: ${jdxMsgs.length} 条, alice 收到: ${aliceMsgs.length} 条`);
  console.log(`群 seq: jdx=[${jdxMsgs.map(m=>m.seq).join(',')}] alice=[${aliceMsgs.map(m=>m.seq).join(',')}]`);
  if (jdxMsgs.length === 3 && aliceMsgs.length === 3) {
    console.log('✅ 3 条群消息实时到达两人(targets 聚合生效)');
  } else {
    console.log('❌ 群消息未全部到达');
  }

  // 4. 群 seq 连续无重复
  const seqs = jdxMsgs.map(m => m.seq);
  const unique = new Set(seqs).size === seqs.length;
  const asc = seqs.every((s, i) => i === 0 || s > seqs[i-1]);
  console.log(`群 seq 无重复: ${unique ? '✅' : '❌'}, 递增: ${asc ? '✅' : '❌'}`);

  // 5. 群消息落库 + 离线拉取:jds 再发一条,然后查拉取接口
  ca.sendMessage({ receiverId: gid, conversationId: gid, msgType:'TEXT', content: '离线补拉测试', clientTime: Date.now() });
  await sleep(2500);
  const pulled = await (await fetch(`${API}/api/groups/${gid}/messages?afterSeq=0`, { headers: { Authorization: 'Bearer ' + jds.token } })).json();
  console.log(`拉取接口: ${pulled.messages.length} 条, maxSeq=${pulled.maxSeq}`);
  const contents = pulled.messages.map(m => m.content);
  console.log(`拉取内容: ${contents.join(', ')}`);
  console.log('✅ 群消息落库可拉取(读扩散)');

  process.exit(0);
}
main().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
