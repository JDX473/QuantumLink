/**
 * QuantumLink 桌面客户端 - 渲染进程(UI 逻辑)
 *
 * 通过 window.quantumlink(preload 暴露)与主进程交互:
 * - 登录/注册 → 拿 token/deviceId → connect
 * - 发消息 → 显示本地待发送 → 收 ACK 更新状态
 * - 收消息 → 渲染报文式气泡
 */

const $ = (sel) => document.querySelector(sel);
const api = window.quantumlink;

// ---- 状态 ----
let currentUser = null;      // 当前登录的 userId
let currentConv = null;      // 当前会话 conversationId
const convs = new Map();     // conversationId → { lastSeq, messages: [] }

// ---- 视图切换 ----
const loginView = $('#login-view');
const mainView = $('#main-view');

// 登录/注册 tab
$('#login-tabs').addEventListener('click', (e) => {
  const tab = e.target.closest('.tab');
  if (!tab) return;
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  tab.classList.add('active');
  currentTab = tab.dataset.tab;
  $('#auth-error').textContent = '';
});

let currentTab = 'login';

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
      const reg = await api.register({ username, password });
      if (!reg || reg.success === false) throw new Error((reg && reg.message) || '注册失败');
      // 注册成功后自动切到登录
      currentTab = 'login';
      document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === 'login'));
      btn.textContent = '连接';
      err.textContent = '注册成功,请登录';
      return;
    }

    // 登录
    const login = await api.login({ username, password, deviceType: 'desktop' });
    currentUser = login.userId;
    await api.connect({ token: login.token, deviceId: login.deviceId });

    // 切主界面
    loginView.classList.add('hidden');
    mainView.classList.remove('hidden');
    $('#link-user').textContent = currentUser;
    setLinkStatus('connecting', '连接中...');
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

api.onConnectionStatus(({ status, userId }) => {
  if (status === 'connected') {
    setLinkStatus('on', '已连接 · ' + userId);
  } else if (status === 'closed') {
    setLinkStatus('', '连接已断开');
  }
});

// ---- 消息渲染 ----
function conversationId(a, b) {
  return a < b ? a + '#' + b : b + '#' + a;
}

function renderMessage(msg, status) {
  const stream = $('#message-stream');
  const isMine = msg.senderId === currentUser;

  const el = document.createElement('div');
  el.className = 'message ' + (isMine ? 'mine' : 'theirs');
  el.dataset.msgId = msg.serverMsgId || msg.clientMsgId || '';
  el.dataset.clientMsgId = msg.clientMsgId || '';

  // 报文头
  const head = document.createElement('div');
  head.className = 'msg-head';
  head.innerHTML = `
    <span class="msg-sender">${escapeHtml(msg.senderId || '?')}</span>
    <span class="msg-seq">seq:${msg.seq ?? '—'}</span>
    <span class="msg-time">${msg.serverTime ? formatTime(msg.serverTime) : ''}</span>
  `;

  // 内容
  const body = document.createElement('div');
  body.className = 'msg-body';
  body.textContent = msg.content || '';

  // 状态脚
  const foot = document.createElement('div');
  foot.className = 'msg-foot';
  const statusText = statusTextFor(status);
  foot.innerHTML = `<div class="msg-status">${statusText}</div>`;

  el.appendChild(head);
  el.appendChild(body);
  el.appendChild(foot);
  stream.appendChild(el);
  stream.scrollTop = stream.scrollHeight;

  // 记录 conv 里的消息,用于状态更新
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
  const foot = el.querySelector('.msg-foot');
  if (foot) foot.innerHTML = `<div class="msg-status">${statusTextFor(status)}</div>`;
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
}

function formatTime(ms) {
  const d = new Date(ms);
  return d.getHours().toString().padStart(2,'0') + ':' + d.getMinutes().toString().padStart(2,'0');
}

// ---- 接收消息 ----
api.onMessage((msg) => {
  const conv = msg.conversationId || conversationId(currentUser, msg.senderId);
  if (!currentConv || currentConv !== conv) {
    currentConv = conv;
    $('#conv-title').textContent = '会话 · ' + conv;
  }
  renderMessage(msg, 'delivered'); // 已收到的消息,显示送达
});

// ---- ACK:更新自己消息的状态 ----
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
  return document.querySelector(`.message[data-client-msg-id="${clientMsgId}"]`);
}
function findMessageByMsgId(msgId) {
  return document.querySelector(`.message[data-msg-id="${msgId}"]`);
}

// ---- 发送 ----
function send() {
  const receiver = $('#composer-receiver').value.trim();
  const content = $('#composer-input').value;
  if (!receiver || !content) return;

  const conv = conversationId(currentUser, receiver);
  if (!currentConv || currentConv !== conv) {
    currentConv = conv;
    $('#conv-title').textContent = '会话 · ' + conv;
  }

  // 本地乐观渲染
  const msg = { senderId: currentUser, receiverId: receiver, content, clientTime: Date.now() };
  const el = renderMessage(msg, 'sending');

  try {
    const cmid = api.send({ receiverId: receiver, content, msgType: 'TEXT' });
    el.dataset.clientMsgId = cmid;
  } catch (e) {
    updateMessageStatus(el, 'failed');
  }

  $('#composer-input').value = '';
  $('#btn-send').disabled = true;
  $('#composer-input').focus();
}

$('#btn-send').addEventListener('click', send);
$('#composer-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});
$('#composer-input').addEventListener('input', () => {
  $('#btn-send').disabled = !$('#composer-input').value.trim() || !$('#composer-receiver').value.trim();
});
$('#composer-receiver').addEventListener('input', () => {
  $('#btn-send').disabled = !$('#composer-input').value.trim() || !$('#composer-receiver').value.trim();
});

// 断开
$('#btn-logout').addEventListener('click', async () => {
  await api.close();
  mainView.classList.add('hidden');
  loginView.classList.remove('hidden');
  currentUser = null;
  currentConv = null;
  $('#message-stream').innerHTML = '';
});
