// 下行发件箱(outbox)验证:服务端未确认队列 + 重推 + 补拉对账回执
//
// 场景① 在线丢推 → 发件箱重推:B 用 raw TCP 收到消息但【不回 DELIVER_ACK】
//   (模拟"推送到了但回执丢了")→ 服务端 10s 后扫描发现未确认、B 在线 → 重推同一条
//   (同 serverMsgId)→ B 收到重复推送后回 DELIVER_ACK → A 收到 DELIVER(已送达)
//   且发件箱出箱(不再有第三次推送)。
// 场景② 离线漏推 → 补拉对账:B 不在线时 A 发消息(不入箱,推送跳过)→ B 上线后
//   增量拉取 → client-core 对拉到的单聊消息自动回 DELIVER_ACK → A 收到 DELIVER。
//
// 前置:chat 必须已用新代码重启(OutboxService 扫描器 + DeliverAckConsumer 出箱),
//       connect 无改动不用重启。云端跑:IM_API/IM_CONNECT_HOST 指向公网。
const net = require('net');
const crypto = require('crypto');
const { FrameType, encode, FrameDecoder } = require('../clients/protocol');
const { ImClient } = require('../clients/client-core');
const API = process.env.IM_API || 'http://127.0.0.1:8081';
const HOST = process.env.IM_CONNECT_HOST || '127.0.0.1';
const PORT = parseInt(process.env.IM_CONNECT_PORT || '19001', 10);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function post(p, b) { const r = await fetch(API + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) }); return r.json(); }
const convIdOf = (a, b) => [a, b].sort().join('#');

/** raw TCP 客户端:可控制"收到消息后是否回 DELIVER_ACK"(发件箱场景必须能装哑巴) */
function rawClient(token, deviceId) {
  return new Promise((resolve) => {
    const dec = new FrameDecoder();
    const sock = net.connect(PORT, HOST);
    const state = {
      authed: false,
      msgs: [],           // 收到的 {serverMsgId, seq, conversationId}
      sock,
      sendAck(msg) {
        sock.write(encode(FrameType.DELIVER_ACK, {
          ackType: 'DELIVER', serverMsgId: msg.serverMsgId, seq: msg.seq, conversationId: msg.conversationId,
        }));
      },
      close() { clearInterval(this.pingTimer); sock.destroy(); },
    };
    state.pingTimer = setInterval(() => sock.write(encode(FrameType.PING, {})), 10000);
    sock.on('connect', () => sock.write(encode(FrameType.HANDSHAKE, { token, deviceId })));
    sock.on('data', (d) => { try { dec.push(d, (f) => {
      if (f.type === FrameType.HANDSHAKE_ACK) { state.authed = f.body.success === true; resolve(state); }
      if (f.type === FrameType.MSG && f.body && f.body.serverMsgId != null) state.msgs.push(f.body);
    }); } catch (e) { console.error('[raw] decode err', e.message); } });
    sock.on('error', () => resolve(state));
  });
}

(async () => {
  let pass1 = false, pass2 = false;
  const s = Date.now() % 100000;

  // ==================== 场景① 在线丢推 → 发件箱重推 ====================
  console.log('== 场景①:在线丢推 → 发件箱重推 ==');
  await post('/api/auth/register', { username: 'oa' + s, password: 'pass123' });
  await post('/api/auth/register', { username: 'ob' + s, password: 'pass123' });
  const A1 = await post('/api/auth/login', { username: 'oa' + s, password: 'pass123', deviceType: 'desktop' });
  const B1 = await post('/api/auth/login', { username: 'ob' + s, password: 'pass123', deviceType: 'desktop' });
  console.log('A1:', A1.userId, '| B1:', B1.userId);

  // B:raw TCP 先上线(在线才有推送,推送成功才入箱);装哑巴不回 DELIVER_ACK
  const bRaw = await rawClient(B1.token, B1.deviceId);
  if (!bRaw.authed) { console.log('FAIL: B1 raw 握手失败'); bRaw.close(); process.exit(1); }
  console.log('[B1] raw TCP 上线(不回 DELIVER_ACK)');

  let a1Delivered = null;
  const a1 = new ImClient({
    host: HOST, port: PORT, token: A1.token, deviceId: A1.deviceId, deviceType: 'desktop', apiBase: API,
    handlers: {
      onDelivered: (ack) => { console.log('[A1] DELIVER 回执:', ack.serverMsgId, 'seq=' + ack.seq); a1Delivered = ack; },
    },
  });
  a1.connect();
  await sleep(500);
  a1.sendMessage({ receiverId: B1.userId, msgType: 'TEXT', content: 'outbox-1-' + s });
  await sleep(500);
  const first = bRaw.msgs[0];
  if (!first) { console.log('FAIL: B1 没收到首次推送'); process.exit(1); }
  console.log('[B1] 首次收到: serverMsgId=' + first.serverMsgId + ' seq=' + first.seq + '(不回 ACK,装丢)');

  // 等发件箱扫描器重推(入箱 10s 后首查,扫描 5s 一轮 → 最多 ~15s,给 25s)
  const dupDeadline = Date.now() + 25000;
  while (Date.now() < dupDeadline && bRaw.msgs.length < 2) await sleep(500);
  const dup = bRaw.msgs[1];
  if (!dup) { console.log('FAIL: 25s 内没收到重推(chat 是否已重启带 @EnableScheduling?)'); process.exit(1); }
  console.log('[B1] 收到重推: serverMsgId=' + dup.serverMsgId + ' seq=' + dup.seq);
  pass1 = dup.serverMsgId === first.serverMsgId; // 重推的必须是同一条消息(幂等)
  if (!pass1) console.log('FAIL: 重推的 serverMsgId 不一致');

  // B 回 DELIVER_ACK → A 收到已送达 + 发件箱出箱
  bRaw.sendAck(first);
  await sleep(2000);
  if (!a1Delivered || a1Delivered.serverMsgId !== first.serverMsgId) console.log('FAIL: A1 没收到 DELIVER');
  else pass1 = pass1 && true;
  // 出箱验证:再等 20s,B1 不应收到第三次推送
  await sleep(20000);
  if (bRaw.msgs.length > 2) { console.log('FAIL: 发件箱未出箱,出现第三次推送'); pass1 = false; }
  console.log('[B1] 出箱验证:20s 内共收到 ' + bRaw.msgs.length + ' 次推送(应=2)');
  bRaw.close(); a1.close();
  console.log('场景①', pass1 ? 'PASS' : 'FAIL');

  // ==================== 场景② 离线漏推 → 补拉对账回执 ====================
  console.log('\n== 场景②:离线漏推 → 补拉对账回执 ==');
  await post('/api/auth/register', { username: 'pa' + s, password: 'pass123' });
  await post('/api/auth/register', { username: 'pb' + s, password: 'pass123' });
  const A2 = await post('/api/auth/login', { username: 'pa' + s, password: 'pass123', deviceType: 'desktop' });
  const B2 = await post('/api/auth/login', { username: 'pb' + s, password: 'pass123', deviceType: 'desktop' });
  console.log('A2:', A2.userId, '| B2:', B2.userId);

  let a2Delivered = null;
  const a2 = new ImClient({
    host: HOST, port: PORT, token: A2.token, deviceId: A2.deviceId, deviceType: 'desktop', apiBase: API,
    handlers: {
      onDelivered: (ack) => { console.log('[A2] DELIVER 回执:', ack.serverMsgId, 'seq=' + ack.seq); a2Delivered = ack; },
    },
  });
  a2.connect();
  await sleep(500);
  // B2 不在线(TCP 未连)→ 推送被跳过、不入箱;消息落库
  a2.sendMessage({ receiverId: B2.userId, msgType: 'TEXT', content: 'outbox-2-' + s });
  await sleep(1500);
  console.log('[A2] 已发(此时 B2 离线,推送跳过,靠补拉)');

  // B2 上线 → 增量拉取 → client-core 自动对拉到的消息回 DELIVER_ACK
  let b2Got = null;
  const b2 = new ImClient({
    host: HOST, port: PORT, token: B2.token, deviceId: B2.deviceId, deviceType: 'desktop', apiBase: API,
    handlers: { onMessage: (m) => { console.log('[B2] 补拉收到:', m.content, 'seq=' + m.seq); b2Got = m; } },
  });
  b2.connect();
  await sleep(800); // 等握手完成(首连不自动补拉,显式调用)
  await b2._pullConversation(convIdOf(A2.userId, B2.userId), 0);
  await sleep(2000);
  if (!b2Got) { console.log('FAIL: B2 补拉没拉到消息'); process.exit(1); }
  pass2 = a2Delivered != null && a2Delivered.serverMsgId === b2Got.serverMsgId;
  if (!pass2) console.log('FAIL: 补拉对账回执没把消息标为已送达');
  b2.close(); a2.close();
  console.log('场景②', pass2 ? 'PASS' : 'FAIL');

  console.log('\n=== 发件箱验证 ' + (pass1 && pass2 ? 'PASS(重推幂等 + 出箱 + 补拉对账)' : 'FAIL') + ' ===');
  process.exit(pass1 && pass2 ? 0 : 1);
})().catch((e) => { console.error('ERR', e); process.exit(1); });
