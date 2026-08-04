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
    api.listGroups({ userId: currentUser }),
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

  // 增量拉取该会话所有消息(从 seq=0 开始,MVP 简单拉全量)
  const data = isGroup
    ? await api.pullGroupMessages({ groupId: conversationId, afterSeq: 0 })
    : await api.pullMessages({ conversationId, afterSeq: 0 });
  for (const m of (data.messages || [])) {
    // 根据消息实际状态渲染:SENT=已存储 / DELIVERED=对方已送达
    // (不能固定 delivered,否则给离线用户发的消息也会显示"已送达")
    renderMessage(m, m.status === 'DELIVERED' ? 'delivered' : 'stored');
  }
  convMessages.set(conversationId, data.messages || []);
}

// ---- 消息渲染 ----
function conversationId(a, b) {
  return a < b ? a + '#' + b : b + '#' + a;
}

function renderMessage(msg, status) {
  const stream = $('#message-stream');
  const isMine = msg.senderId === currentUser;

  const el = document.createElement('div');
  el.className = 'message-row ' + (isMine ? 'mine' : 'theirs');
  el.dataset.msgId = msg.serverMsgId || '';
  el.dataset.clientMsgId = msg.clientMsgId || '';

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
  // 状态灯 + 时间;seq 保留在 data 里用于调试,不展示
  const timeText = msg.serverTime ? formatTime(msg.serverTime) : '';
  meta.innerHTML = `<span class="msg-status">${statusTextFor(status)}</span><span class="msg-time">${escapeHtml(timeText)}</span>`;

  const bubble = document.createElement('div');
  bubble.className = 'msg-bubble';
  bubble.appendChild(body);
  bubble.appendChild(meta);

  el.appendChild(avatarHtml ? (() => { const a = document.createElement('span'); a.className='msg-avatar-wrap'; a.innerHTML = avatarHtml; return a; })() : document.createElement('span'));
  el.appendChild(bubble);
  stream.appendChild(el);
  stream.scrollTop = stream.scrollHeight;
  return el;
}

function statusTextFor(status) {
  switch (status) {
    case 'sending': return `<span class="sending"><span class="status-dot"></span>发送中</span>`;
    case 'stored': return `<span class="stored"><span class="status-dot"></span>已存储</span>`;
    case 'delivered': return `<span class="delivered"><span class="status-dot"></span>对方已送达</span>`;
    case 'failed': return `<span class="failed"><span class="status-dot"></span>发送失败</span>`;
    default: return '';
  }
}

function updateMessageStatus(el, status) {
  if (!el) return;
  const statusEl = el.querySelector('.msg-status');
  if (statusEl) statusEl.innerHTML = statusTextFor(status);
}

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
  // 是当前会话 → 直接渲染
  if (currentConv === msg.conversationId) {
    renderMessage(msg, 'delivered');
  }
  // 更新会话列表预览
  loadConversations();
});

// ---- ACK / DELIVER:更新自己消息的状态 ----
api.onAck((ack) => {
  if (ack.ackType === 'STORE') {
    const el = findMessageByClientId(ack.clientMsgId);
    if (el) { updateMessageStatus(el, 'stored'); el.dataset.msgId = ack.serverMsgId; }
  }
});

api.onDelivered((ack) => {
  const el = findMessageByMsgId(ack.serverMsgId) || findMessageByClientId(ack.clientMsgId);
  if (el) updateMessageStatus(el, 'delivered');
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

  const msg = { senderId: currentUser, senderAvatar: currentAvatar, receiverId, content, clientTime: Date.now() };
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
    const created = await api.createGroup({ name, ownerId: currentUser, members: memberIds });
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
