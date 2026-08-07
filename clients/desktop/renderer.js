/**
 * QuantumLink 桌面客户端 - 渲染进程(UI 逻辑)
 *
 * 交互模型对齐微信/Discord:
 * - 左侧会话列表(点击选中)
 * - 选中会话 → 右侧消息流 → 输入框直接发(无需填接收方)
 * - 新会话:＋按钮 → 输入用户名 → 解析成 userId → 建会话
 */

const $ = (sel) => document.querySelector(sel);
const api = window.quantumlink;

// ---- 状态 ----
let currentUser = null;        // 当前登录的 userId
let currentUsername = null;    // 当前登录的用户名
let currentAvatar = null;      // 当前登录的头像 URL
let currentConv = null;        // 当前会话 conversationId
let currentConvIsGroup = false; // 当前会话是否为群
let convMessages = new Map();  // conversationId → { messages: [] }
let peerReadSeqByConv = new Map(); // conversationId → 对端已读水位(对方读到哪条 seq)

// ---- 视图切换 ----
const loginView = $('#login-view');
const mainView = $('#main-view');
let currentTab = 'login';

// 登录/注册 tab
$('.login-tabs').addEventListener('click', (e) => {
  const tab = e.target.closest('.tab');
  if (!tab) return;
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  tab.classList.add('active');
  currentTab = tab.dataset.tab;
  $('#auth-error').textContent = '';
  // 头像上传字段只在注册 tab 显示
  $('#avatar-field').style.display = (currentTab === 'register') ? 'flex' : 'none';
});

// 表单提交
$('#auth-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const username = $('#username').value.trim();
  const password = $('#password').value;
  const btn = $('#auth-submit');
  const err = $('#auth-error');
  if (!username || !password) { err.textContent = '请输入用户名和密码'; return; }

  btn.disabled = true;
  btn.textContent = currentTab === 'login' ? '连接中...' : '注册中...';
  err.textContent = '';

  try {
    if (currentTab === 'register') {
      // 如果有头像文件,走 multipart 注册
      const avatarFile = $('#avatar-file').files[0];
      if (avatarFile) {
        const fileData = await fileToBase64(avatarFile);
        const reg = await api.registerWithAvatar({
          username, password,
          fileData, fileName: avatarFile.name, mimeType: avatarFile.type,
        });
        if (!reg || reg.success === false) throw new Error((reg && reg.message) || '注册失败');
      } else {
        const reg = await api.register({ username, password });
        if (!reg || reg.success === false) throw new Error((reg && reg.message) || '注册失败');
      }
      currentTab = 'login';
      document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === 'login'));
      btn.textContent = '连接';
      err.textContent = '注册成功,请登录';
      return;
    }

    const login = await api.login({ username, password, deviceType: 'desktop' });
    currentUser = login.userId;
    currentUsername = login.username || username;
    currentAvatar = login.avatarUrl || null;
    await api.connect({ token: login.token, deviceId: login.deviceId });

    loginView.classList.add('hidden');
    mainView.classList.remove('hidden');
    $('#link-user').textContent = currentUsername; // 显示用户名
    showMyAvatar();
    setLinkStatus('connecting', '连接中...');

    await loadConversations(); // 拉会话列表
  } catch (ex) {
    err.textContent = ex.message || '连接失败';
  } finally {
    btn.disabled = false;
    btn.textContent = currentTab === 'login' ? '连接' : '注册';
  }
});

// ---- 连接状态 ----
function setLinkStatus(state, text) {
  const light = $('#link-light');
  light.className = 'light';
  if (state === 'on') light.classList.add('on');
  if (state === 'connecting') light.classList.add('connecting');
  $('#link-text').textContent = text;
}

/** 显示自己的头像(顶部) */
function showMyAvatar() {
  const img = $('#me-avatar');
  const fallback = $('#me-avatar-fallback');
  if (currentAvatar) {
    img.src = currentAvatar;
    img.style.display = '';
    fallback.style.display = 'none';
  } else {
    img.style.display = 'none';
    fallback.style.display = 'inline-flex';
    fallback.textContent = (currentUsername || '?')[0];
  }
}

/** 点击自己的头像 → 选择文件修改头像 */
$('#me-avatar').addEventListener('click', () => $('#me-avatar-file').click());
$('#me-avatar-fallback').addEventListener('click', () => $('#me-avatar-file').click());
$('#me-avatar-file').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  if (!file || !currentUser) return;
  try {
    const fileData = await fileToBase64(file);
    const result = await api.updateAvatar({
      userId: currentUser,
      fileData, fileName: file.name, mimeType: file.type,
    });
    if (result && result.success) {
      currentAvatar = result.avatarUrl;
      showMyAvatar();
      // 更新会话列表里自己的头像预览
      loadConversations();
    } else {
      console.error('改头像失败:', result);
    }
  } catch (ex) {
    console.error('改头像异常:', ex.message);
  }
  e.target.value = ''; // 允许重新选同一文件
});

api.onConnectionStatus(({ status, userId }) => {
  if (status === 'connected') setLinkStatus('on', '已连接');
  else if (status === 'closed') setLinkStatus('', '连接已断开');
});

// ---- 会话列表(单聊 + 群)----
async function loadConversations() {
  const [data, groups] = await Promise.all([
    api.listConversations({ userId: currentUser }),
    api.listGroups(),
  ]);
  const list = $('#conv-list');
  list.innerHTML = '';

  if ((!data.conversations || data.conversations.length === 0) && (!groups.groups || groups.groups.length === 0)) {
    list.innerHTML = '<div class="conv-empty">还没有会话,点 ＋ 发起新会话</div>';
    return;
  }

  // 群会话(群名 + 群成员数)
  for (const g of (groups.groups || [])) {
    const item = document.createElement('div');
    item.className = 'conv-item';
    item.dataset.convId = g.groupId;
    item.dataset.isGroup = '1';
    item.innerHTML = `
      <div class="conv-item-row">
        <span class="conv-avatar conv-avatar-placeholder">群</span>
        <div class="conv-item-body">
          <div class="conv-item-top">
            <span class="conv-item-name">${escapeHtml(g.name)}</span>
          </div>
          <div class="conv-item-preview">群聊</div>
        </div>
      </div>
    `;
    item.addEventListener('click', () => openConversation(g.groupId, null, g.name, true));
    list.appendChild(item);
  }

  // 单聊会话
  for (const conv of data.conversations) {
    const item = document.createElement('div');
    item.className = 'conv-item';
    item.dataset.convId = conv.conversationId;
    item.dataset.peer = conv.peerUserId;
    const avatarHtml = conv.peerAvatar
      ? `<img class="conv-avatar" src="${escapeHtml(conv.peerAvatar)}" alt="">`
      : `<span class="conv-avatar conv-avatar-placeholder">${escapeHtml((conv.peerUsername || '?')[0])}</span>`;
    item.innerHTML = `
      <div class="conv-item-row">
        ${avatarHtml}
        <div class="conv-item-body">
          <div class="conv-item-top">
            <span class="conv-item-name">${escapeHtml(conv.peerUsername || conv.peerUserId)}</span>
            <span class="conv-item-time">${conv.lastTime ? formatTime(conv.lastTime) : ''}</span>
          </div>
          <div class="conv-item-preview">${escapeHtml(conv.lastMessage || '')}</div>
        </div>
      </div>
    `;
    item.addEventListener('click', () => openConversation(conv.conversationId, conv.peerUserId, conv.peerUsername, false));
    list.appendChild(item);
  }
}

// ---- 打开会话(单聊 / 群)----
async function openConversation(conversationId, peerUserId, peerUsername, isGroup) {
  currentConv = conversationId;
  currentConvIsGroup = !!isGroup;
  $('#conv-title').textContent = peerUsername || peerUserId || '群聊';
  $('#message-stream').innerHTML = '';

  // 高亮选中
  document.querySelectorAll('.conv-item').forEach(el =>
    el.classList.toggle('active', el.dataset.convId === conversationId));

  // 会话内缓存(convMessages)已按"增量"维护:实时消息进缓存,重开只拉游标之后的新消息。
  // 首次打开(本会话内无缓存)加载最近 N 条(单聊50/群聊100,对齐微信:打开看最近,更早走"查看更早的消息")。
  const peerReadRef = { value: 0 };
  let all;

  const cached = convMessages.get(conversationId) || [];
  if (cached.length === 0) {
    // 首次打开:只拉最近 N 条(增量思路:不全量历史)
    all = isGroup ? await pullGroupTail(conversationId) : await pullTail(conversationId, peerReadRef);
  } else {
    // 再次打开:增量拉取游标(=缓存最大 seq)之后的新消息,合并缓存
    const cursor = cached.reduce((mx, m) => Math.max(mx, m.seq || 0), 0);
    const fresh = isGroup ? await pullGroupAll(conversationId, cursor) : await pullAfter(conversationId, cursor, peerReadRef);
    all = mergeMessages(cached, fresh);
  }

  // 对端已读水位:自己的消息 seq ≤ 该水位 → "对方已读"
  // (实时 READ 事件管当下,这里管历史——对端离线期间读的靠下拉补回来)
  peerReadSeqByConv.set(conversationId, peerReadRef.value);

  let maxDisplayedSeq = 0;
  for (const m of all) {
    // 自己的消息按实际状态渲染:SENT=已存储 / DELIVERED=对方已送达 / 对端水位已过=对方已读
    // 别人发的消息不标状态(status='')——每个用户只关心自己发的消息状态
    const status = statusForMsg(m, peerReadRef.value);
    renderMessage(m, status);
    if (m.seq > maxDisplayedSeq) maxDisplayedSeq = m.seq;
  }
  convMessages.set(conversationId, all);

  // 打开会话 = 看到了这些消息 → 上报已读(单聊推给对端;群聊推进成员水位 + 预聚合计数)
  if (maxDisplayedSeq > 0) reportRead(conversationId, maxDisplayedSeq);
  updateLoadOlderButton();
}

/** 首次打开会话加载的最近消息条数(对齐微信:显示最近,历史靠向上翻) */
const TAIL_LIMIT = 50;

/** 拉取 afterSeq 之后的全部消息(分页到 hasMore=false),回填对端水位 */
async function pullAfter(conversationId, afterSeq, peerReadRef) {
  let all = [];
  let page;
  do {
    page = await api.pullMessages({ conversationId, afterSeq });
    if (page.peerReadSeq != null) peerReadRef.value = page.peerReadSeq;
    const msgs = page.messages || [];
    if (msgs.length) {
      all = all.concat(msgs);
      afterSeq = msgs[msgs.length - 1].seq;
    }
    if (all.length > 2000) break; // 防御上限,防超大会话拖死 UI
  } while (page.hasMore);
  return all;
}

/** 首次打开:加载最近 TAIL_LIMIT 条。先拿 serverMaxSeq,再从 maxSeq-N 往后拉(不全量) */
async function pullTail(conversationId, peerReadRef) {
  const probe = await api.pullMessages({ conversationId, afterSeq: 0 });
  if (probe.peerReadSeq != null) peerReadRef.value = probe.peerReadSeq;
  const maxSeq = probe.serverMaxSeq || 0;
  if (maxSeq <= TAIL_LIMIT) return probe.messages || [];
  return pullAfter(conversationId, Math.max(0, maxSeq - TAIL_LIMIT), peerReadRef);
}

// ---- 群聊增量同步 ----

/** 群聊首次打开加载的最近消息条数(小群增量同步:打开看最近 100 条,更早走"查看更早的消息") */
const GROUP_TAIL_LIMIT = 100;
/** 每次"查看更早的消息"加载的条数 */
const LOAD_MORE_LIMIT = 100;

/** 拉取群消息 afterSeq 之后的所有消息(分页到 hasMore=false) */
async function pullGroupAll(groupId, afterSeq) {
  let all = [];
  let page;
  do {
    page = await api.pullGroupMessages({ groupId, afterSeq, limit: LOAD_MORE_LIMIT });
    const msgs = page.messages || [];
    if (msgs.length) {
      all = all.concat(msgs);
      afterSeq = msgs[msgs.length - 1].seq;
    }
    if (all.length > 2000) break; // 防御上限,防超大会话拖死 UI
  } while (page.hasMore);
  return all;
}

/** 首次打开群:加载最近 GROUP_TAIL_LIMIT 条。先拿 maxSeq,再从 maxSeq-N 往后拉(不全量) */
async function pullGroupTail(groupId) {
  const probe = await api.pullGroupMessages({ groupId, afterSeq: 0, limit: GROUP_TAIL_LIMIT });
  const maxSeq = probe.maxSeq || 0;
  if (maxSeq <= GROUP_TAIL_LIMIT) return probe.messages || []; // 短会话:一次全拿
  return pullGroupAll(groupId, Math.max(0, maxSeq - GROUP_TAIL_LIMIT));
}

/** 上报已读:本端看到的最大 seq(打开会话 / 会话中收到新消息渲染后调用) */
function reportRead(conversationId, untilSeq) {
  if (!conversationId || !untilSeq) return;
  api.reportRead({ conversationId, untilSeq }).catch(() => {});
}

// ---- 消息渲染 ----
function conversationId(a, b) {
  return a < b ? a + '#' + b : b + '#' + a;
}

/** 构建单条消息 DOM(不 append、不滚动)——渲染 append 用,加载更早前插也用 */
function buildMessageEl(msg, status) {
  const isMine = msg.senderId === currentUser;

  const el = document.createElement('div');
  el.className = 'message-row ' + (isMine ? 'mine' : 'theirs');
  el.dataset.msgId = msg.serverMsgId || '';
  el.dataset.clientMsgId = msg.clientMsgId || '';
  el.dataset.seq = msg.seq || ''; // 已读判定用:对端水位 ≥ seq → 已读
  el.dataset.status = status; // 状态只进不退还需要的当前值

  // 头像(气泡外侧):自己右侧,对方左侧
  const displayName = isMine ? '我' : (msg.senderName || msg.senderId || '?');
  const avatarHtml = msg.senderAvatar
    ? `<img class="msg-avatar" src="${escapeHtml(msg.senderAvatar)}" alt="">`
    : `<span class="msg-avatar msg-avatar-placeholder">${escapeHtml((displayName || '?')[0])}</span>`;

  // 气泡:只有内容 + 状态(微信式,seq/时间/用户名都收进气泡下方小字)
  const body = document.createElement('div');
  body.className = 'msg-body';
  body.textContent = msg.content || '';

  const meta = document.createElement('div');
  meta.className = 'msg-meta';
  // 状态标签:只给自己的消息标(发送中/已存储/对方已送达/对方已读);别人发的消息(status='')不标
  // 群聊消息:显示"n人已读"(自己发的排除自己;readCount 来自拉取接口预聚合,实时消息暂无)
  // 时间始终显示
  const timeText = msg.serverTime ? formatTime(msg.serverTime) : '';
  const statusHtml = status ? `<span class="msg-status">${statusTextFor(status)}</span>` : '';
  let readHtml = '';
  if (currentConvIsGroup && msg.readCount != null) {
    const n = msg.senderId === currentUser ? (msg.readCount - 1) : msg.readCount;
    readHtml = `<span class="msg-read">${Math.max(0, n)}人已读</span>`;
  }
  meta.innerHTML = statusHtml + readHtml + `<span class="msg-time">${escapeHtml(timeText)}</span>`;

  const bubble = document.createElement('div');
  bubble.className = 'msg-bubble';
  bubble.appendChild(body);
  bubble.appendChild(meta);

  el.appendChild(avatarHtml ? (() => { const a = document.createElement('span'); a.className='msg-avatar-wrap'; a.innerHTML = avatarHtml; return a; })() : document.createElement('span'));
  el.appendChild(bubble);
  return el;
}

function renderMessage(msg, status) {
  const stream = $('#message-stream');
  const el = buildMessageEl(msg, status);
  stream.appendChild(el);
  stream.scrollTop = stream.scrollHeight;
  return el;
}

function statusTextFor(status) {
  switch (status) {
    case 'sending': return `<span class="sending"><span class="status-dot"></span>发送中</span>`;
    case 'stored': return `<span class="stored"><span class="status-dot"></span>已存储</span>`;
    case 'delivered': return `<span class="delivered"><span class="status-dot"></span>对方已送达</span>`;
    case 'read': return `<span class="read"><span class="status-dot"></span>对方已读</span>`;
    case 'failed': return `<span class="failed"><span class="status-dot"></span>发送失败</span>`;
    default: return '';
  }
}

// 消息状态只进不退:发送中 < 已存储 < 对方已送达 < 对方已读(failed 仅限发送中)。
// 防止两个竞态把"已读"降级回"已送达":① DELIVER 事件在 READ 之后到达;
// ② 连发消息时 READ/ACK 乱序,先 READ 后 ACK 的消息靠 onAck 里对端水位补判。
const STATUS_RANK = { sending: 0, stored: 1, delivered: 2, read: 3, failed: 99 };

function updateMessageStatus(el, status) {
  if (!el) return;
  const cur = el.dataset.status || 'sending';
  if (status === 'failed') {
    if (cur !== 'sending') return; // 已存储/已送达的消息不会再失败
  } else if (STATUS_RANK[status] <= (STATUS_RANK[cur] ?? 0)) {
    return; // 只进不退:已读的消息不会被 DELIVER 降级
  }
  el.dataset.status = status;
  const statusEl = el.querySelector('.msg-status');
  if (statusEl) statusEl.innerHTML = statusTextFor(status);
}

/** 消息状态:自己的消息按实际状态(SENT=已存储/DELIVERED=已送达/对端水位已过=已读);别人的不标 */
function statusForMsg(m, peerRead) {
  if (m.senderId !== currentUser) return '';
  return (m.seq <= peerRead ? 'read' : (m.status === 'DELIVERED' ? 'delivered' : 'stored'));
}

/** 合并消息:按 serverMsgId 去重 + 按 seq 升序 */
function mergeMessages(a, b) {
  const seen = new Set(a.map(m => m.serverMsgId));
  const out = a.slice();
  for (const m of b) {
    if (m && !seen.has(m.serverMsgId)) { seen.add(m.serverMsgId); out.push(m); }
  }
  out.sort((x, y) => (x.seq || 0) - (y.seq || 0));
  return out;
}

// ---- 查询更早的聊天记录(向上翻,单聊/群聊通用)----

/** 当前会话是否还有更早的消息(缓存最小 seq > 1) */
function hasOlderMessages() {
  if (!currentConv) return false;
  const cached = convMessages.get(currentConv) || [];
  if (!cached.length) return false;
  const minSeq = cached.reduce((mn, m) => Math.min(mn, m.seq || Infinity), Infinity);
  return isFinite(minSeq) && minSeq > 1;
}

/** 更新"查看更早的消息"按钮显隐 */
function updateLoadOlderButton() {
  const btn = $('#load-older-btn');
  if (btn) btn.classList.toggle('hidden', !hasOlderMessages());
}

/** 加载更早的聊天记录:拉当前最小 seq 之前的 LOAD_MORE_LIMIT 条,前插到消息流 */
async function loadOlderMessages() {
  const convId = currentConv;
  if (!convId) return;
  const cached = convMessages.get(convId) || [];
  const minSeq = cached.reduce((mn, m) => Math.min(mn, m.seq || Infinity), Infinity);
  if (!isFinite(minSeq) || minSeq <= 1) { updateLoadOlderButton(); return; }

  const afterSeq = Math.max(0, minSeq - LOAD_MORE_LIMIT);
  const page = currentConvIsGroup
    ? await api.pullGroupMessages({ groupId: convId, afterSeq, limit: LOAD_MORE_LIMIT })
    : await api.pullMessages({ conversationId: convId, afterSeq, limit: LOAD_MORE_LIMIT });
  const older = (page.messages || []).filter(m => m.seq < minSeq); // 排除已加载的边界重复
  if (!older.length) { updateLoadOlderButton(); return; }

  const merged = mergeMessages(cached, older);
  convMessages.set(convId, merged);

  // 前插:older 升序,倒序 prepend 保持整体升序(旧的在上、新的在下)
  const stream = $('#message-stream');
  const peerRead = peerReadSeqByConv.get(convId) || 0;
  const nodes = older.map(m => buildMessageEl(m, statusForMsg(m, peerRead)));
  nodes.reverse().forEach(n => stream.prepend(n));
  updateLoadOlderButton();
}

$('#load-older-btn').addEventListener('click', () => loadOlderMessages());

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
}

/** 文件转 base64(传给主进程做 multipart 上传) */
function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      // FileReader 结果是 data:image/png;base64,xxx → 取逗号后部分
      const dataUrl = reader.result;
      const base64 = dataUrl.split(',')[1] || '';
      resolve(base64);
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

function formatTime(ms) {
  const d = new Date(ms);
  return d.getHours().toString().padStart(2,'0') + ':' + d.getMinutes().toString().padStart(2,'0');
}

// ---- 接收消息(实时推送) ----
api.onMessage((msg) => {
  // 是当前会话 → 直接渲染。对方发的消息不标状态(status='' 只显示时间)。
  if (currentConv === msg.conversationId) {
    renderMessage(msg, '');
    // 会话打开中收到对端消息 → 渲染了就算已读,上报(单聊推给对端;群聊推进成员水位+计数)
    if (msg.senderId !== currentUser && msg.seq) {
      reportRead(currentConv, msg.seq);
    }
  }
  // 维护会话缓存(按 serverMsgId 去重):后续重开该会话只做增量拉取,不全量
  if (msg && msg.conversationId) {
    const cached = convMessages.get(msg.conversationId) || [];
    if (!cached.some(m => m.serverMsgId === msg.serverMsgId)) {
      cached.push(msg);
      convMessages.set(msg.conversationId, cached);
    }
  }
  // 更新会话列表预览
  loadConversations();
});

// ---- ACK / DELIVER:更新自己消息的状态 ----
api.onAck((ack) => {
  if (ack.ackType === 'STORE') {
    const el = findMessageByClientId(ack.clientMsgId);
    if (el) {
      el.dataset.msgId = ack.serverMsgId;
      if (ack.seq) {
        el.dataset.seq = ack.seq; // 补服务端 seq:onRead 按它判定已读(发送时 seq 未知)
        // 若对端水位已 ≥ 该 seq(READ 事件先到、当时没 seq 漏标)→ 直接"已读",否则"已存储"
        const peerRead = peerReadSeqByConv.get(ack.conversationId) || 0;
        updateMessageStatus(el, ack.seq <= peerRead ? 'read' : 'stored');
      }
    }
  }
});

api.onDelivered((ack) => {
  const el = findMessageByMsgId(ack.serverMsgId) || findMessageByClientId(ack.clientMsgId);
  if (el) updateMessageStatus(el, 'delivered'); // 只进不退:已读的消息不会被降级回已送达
});

// ---- READ 事件:对端读了本会话的消息,水位推进 → 重渲染自己的消息 ----
api.onRead((read) => {
  if (!read || !read.conversationId || read.untilSeq == null) return;
  const conv = read.conversationId;
  // 只进不退(多端/乱序防护)
  const prev = peerReadSeqByConv.get(conv) || 0;
  if (read.untilSeq <= prev) return;
  peerReadSeqByConv.set(conv, read.untilSeq);

  // 当前会话:自己 ≤ 对端水位的消息标记为"对方已读"
  if (currentConv === conv && !currentConvIsGroup) {
    document.querySelectorAll('.message-row.mine').forEach((el) => {
      const seq = Number(el.dataset.seq);
      if (!isNaN(seq) && seq > 0 && seq <= read.untilSeq) {
        updateMessageStatus(el, 'read');
      }
    });
  }
});

// ---- 群已读计数更新:别人读了我的群消息 → 实时更新"n人已读"(只推给发送者,非群广播) ----
api.onGroupRead((read) => {
  if (!read || !read.conversationId || read.seq == null || read.readCount == null) return;
  if (currentConv !== read.conversationId || !currentConvIsGroup) return;
  const el = document.querySelector(`.message-row[data-seq="${read.seq}"]`);
  if (!el) return;
  // 自己发的消息显示"其他已读人数"(count-1);别人发的显示总数
  const senderIsMe = el.classList.contains('mine');
  const n = senderIsMe ? (read.readCount - 1) : read.readCount;
  const text = Math.max(0, n) + '人已读';
  let readEl = el.querySelector('.msg-read');
  if (readEl) {
    readEl.textContent = text;
  } else {
    const meta = el.querySelector('.msg-meta');
    readEl = document.createElement('span');
    readEl.className = 'msg-read';
    readEl.textContent = text;
    meta.insertBefore(readEl, meta.firstChild);
  }
});

api.onSendFailed((msg) => {
  const el = findMessageByClientId(msg.clientMsgId);
  if (el) updateMessageStatus(el, 'failed');
});

function findMessageByClientId(clientMsgId) {
  return document.querySelector(`.message-row[data-client-msg-id="${clientMsgId}"]`);
}
function findMessageByMsgId(msgId) {
  return document.querySelector(`.message-row[data-msg-id="${msgId}"]`);
}

// ---- 发送(当前会话,无需填接收方) ----
function send() {
  const content = $('#composer-input').value;
  if (!content || !currentConv) return;

  // 群聊:receiverId/conversationId 都是群 id;单聊:从会话解析对方 userId
  let receiverId;
  if (currentConvIsGroup) {
    receiverId = currentConv;
  } else {
    const [a, b] = currentConv.split('#');
    receiverId = a === currentUser ? b : a;
  }

  const msg = { senderId: currentUser, senderAvatar: currentAvatar, receiverId, content, clientTime: Date.now(), readCount: 0 };
  const el = renderMessage(msg, 'sending');

  api.send({ receiverId, conversationId: currentConv, content, msgType: 'TEXT' })
    .then((cmid) => { if (cmid) el.dataset.clientMsgId = cmid; })
    .catch(() => updateMessageStatus(el, 'failed'));

  $('#composer-input').value = '';
  $('#btn-send').disabled = true;
  $('#composer-input').focus();
}

$('#btn-send').addEventListener('click', send);
$('#composer-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});
$('#composer-input').addEventListener('input', () => {
  $('#btn-send').disabled = !$('#composer-input').value.trim();
});

// ---- 新会话弹窗 ----
$('#btn-new-conv').addEventListener('click', () => {
  $('#new-conv-modal').classList.remove('hidden');
  $('#new-conv-username').value = '';
  $('#new-conv-error').textContent = '';
  $('#new-conv-username').focus();
});
$('#btn-new-conv-cancel').addEventListener('click', () => {
  $('#new-conv-modal').classList.add('hidden');
});
$('#btn-new-conv-ok').addEventListener('click', async () => {
  const username = $('#new-conv-username').value.trim();
  const err = $('#new-conv-error');
  if (!username) { err.textContent = '请输入用户名'; return; }
  try {
    const resolved = await api.resolveUser({ username });
    if (!resolved.success) { err.textContent = '用户不存在: ' + username; return; }
    const conv = conversationId(currentUser, resolved.userId);
    $('#new-conv-modal').classList.add('hidden');
    await openConversation(conv, resolved.userId, resolved.username);
    await loadConversations();
    $('#composer-input').focus();
  } catch (ex) {
    err.textContent = ex.message || '解析失败';
  }
});
// Enter 触发
$('#new-conv-username').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') $('#btn-new-conv-ok').click();
});

// 创建群:群名 + 成员用户名(逗号分隔,逐个 resolve 成 userId)
$('#btn-new-group-ok').addEventListener('click', async () => {
  const name = $('#new-group-name').value.trim();
  const membersText = $('#new-group-members').value.trim();
  const err = $('#new-conv-error');
  if (!name) { err.textContent = '请输入群名'; return; }
  try {
    const memberIds = [];
    if (membersText) {
      for (const uname of membersText.split(/[,，]/).map(s => s.trim()).filter(Boolean)) {
        const resolved = await api.resolveUser({ username: uname });
        if (!resolved.success) { err.textContent = '成员不存在: ' + uname; return; }
        memberIds.push(resolved.userId);
      }
    }
    const created = await api.createGroup({ name, members: memberIds });
    if (!created.success) { err.textContent = created.message || '建群失败'; return; }
    $('#new-conv-modal').classList.add('hidden');
    $('#new-group-name').value = '';
    $('#new-group-members').value = '';
    await loadConversations();
    await openConversation(created.groupId, null, created.name, true);
    $('#composer-input').focus();
  } catch (ex) {
    err.textContent = ex.message || '建群失败';
  }
});

// 面对面建群:输入 4 位数字,加入/创建该数字窗口的群
$('#btn-new-f2f-ok').addEventListener('click', async () => {
  const code = $('#new-f2f-code').value.trim();
  const err = $('#new-conv-error');
  if (!/^\d{4}$/.test(code)) { err.textContent = '请输入 4 位数字'; return; }
  try {
    const joined = await api.face2face({ code });
    if (!joined.success) { err.textContent = joined.message || '加入失败'; return; }
    $('#new-conv-modal').classList.add('hidden');
    $('#new-f2f-code').value = '';
    await loadConversations();
    await openConversation(joined.groupId, null, joined.name, true);
    $('#composer-input').focus();
  } catch (ex) {
    err.textContent = ex.message || '面对面建群失败';
  }
});

// 断开
$('#btn-logout').addEventListener('click', async () => {
  await api.close();
  mainView.classList.add('hidden');
  loginView.classList.remove('hidden');
  currentUser = null;
  currentConv = null;
  $('#message-stream').innerHTML = '';
  $('#conv-list').innerHTML = '';
});
