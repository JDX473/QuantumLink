/**
 * QuantumLink 桌面客户端 - Electron 主进程
 *
 * 职责:
 * - 创建桌面窗口
 * - 在主进程维护 TCP 长连接(复用 clients/client-core.js 的 ImClient)
 * - 通过 preload 的 contextBridge 把客户端能力暴露给渲染进程(UI)
 *
 * 为什么 TCP 放主进程:
 * 渲染进程默认无 Node 能力(安全),原生 TCP 只能在主进程(Node 环境)跑。
 * 主进程作为"连接层",渲染进程作为"UI 层",通过 IPC 通信——和
 * 服务端 im-connect(连接) / im-chat(业务) 的分层思想一致。
 */
const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

// 复用自定义 TCP 客户端核心(握手/心跳/重连/重传/增量拉取)
const { ImClient } = require('../client-core.js');

// 业务层 API 地址(可用 IM_API / IM_CONNECT_HOST / IM_CONNECT_PORT 覆盖,云部署用)
const API = process.env.IM_API || 'http://8.141.86.246:8081';
const CONNECT_HOST = process.env.IM_CONNECT_HOST || '8.141.86.246';
const CONNECT_PORT = process.env.IM_CONNECT_PORT || '19001';

let mainWindow = null;
let client = null;
let authToken = null; // 登录后保存,HTTP 接口鉴权用

/** 带鉴权的 fetch:自动附加 Authorization: Bearer {token} */
function authFetch(url, options) {
  const headers = { ...(options?.headers || {}) };
  if (authToken) headers['Authorization'] = 'Bearer ' + authToken;
  return fetch(url, { ...options, headers });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 980,
    height: 720,
    minWidth: 760,
    minHeight: 560,
    backgroundColor: '#0B0F1A',
    titleBarStyle: 'hidden',
    titleBarOverlay: {
      color: '#0B0F1A',
      symbolColor: '#8B93A7',
      height: 44,
    },
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  mainWindow.loadFile(path.join(__dirname, 'index.html'));
}

// ---- IPC:渲染进程 → 主进程 ----

/** 登录:调业务层 HTTP 拿 token/deviceId,再建立长连接(deviceId 客户端持久,重装/重登不变) */
ipcMain.handle('auth:login', async (_e, { username, password, deviceType, deviceId }) => {
  const res = await fetch(API + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, deviceType: deviceType || 'desktop', deviceId }),
  });
  const data = await res.json();
  if (!data.success) throw new Error(data.message || 'login failed');
  authToken = data.token; // 保存 token,后续 HTTP 接口鉴权
  return { token: data.token, deviceId: data.deviceId, userId: data.userId, username: data.username, avatarUrl: data.avatarUrl };
});

/** 注册 */
ipcMain.handle('auth:register', async (_e, { username, password }) => {
  const res = await fetch(API + '/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const data = await res.json();
  if (!data.success) throw new Error(data.message || 'register failed');
  return data;
});

/** 注册(带头像,multipart 上传) */
ipcMain.handle('auth:register-with-avatar', async (_e, { username, password, fileData, fileName, mimeType }) => {
  const fd = new FormData();
  fd.append('username', username);
  fd.append('password', password);
  // 把 base64 解码成二进制
  const buf = Buffer.from(fileData, 'base64');
  const blob = new Blob([buf], { type: mimeType || 'image/png' });
  fd.append('file', blob, fileName || 'avatar.png');

  const res = await fetch(API + '/api/auth/register/avatar', {
    method: 'POST',
    body: fd,
  });
  const data = await res.json();
  if (!data.success) throw new Error(data.message || 'register failed');
  return data;
});

/** 用户名 → userId 解析(聊天时填用户名,解析成 userId 再发) */
ipcMain.handle('users:resolve', async (_e, { username }) => {
  const res = await authFetch(`${API}/api/users/resolve?username=${encodeURIComponent(username)}`);
  const data = await res.json();
  if (!data.success) throw new Error(data.message || 'resolve failed');
  return data;
});

/** 修改头像(multipart 上传) */
ipcMain.handle('users:update-avatar', async (_e, { userId, fileData, fileName, mimeType }) => {
  const fd = new FormData();
  const buf = Buffer.from(fileData, 'base64');
  const blob = new Blob([buf], { type: mimeType || 'image/png' });
  fd.append('file', blob, fileName || 'avatar.png');

  const res = await authFetch(`${API}/api/users/${encodeURIComponent(userId)}/avatar`, {
    method: 'POST',
    body: fd,
  });
  const data = await res.json();
  if (!data.success) throw new Error(data.message || 'upload failed');
  return data;
});

/** 会话列表 */
ipcMain.handle('convs:list', async (_e, { userId }) => {
  const res = await authFetch(`${API}/api/conversations?userId=${encodeURIComponent(userId)}`);
  const data = await res.json();
  return data;
});

/** 拉取某会话 afterSeq 之后的消息 */
ipcMain.handle('convs:pull', async (_e, { conversationId, afterSeq, limit }) => {
  const res = await authFetch(`${API}/api/conversations/${encodeURIComponent(conversationId)}/messages?afterSeq=${afterSeq}&limit=${limit || 50}`);
  const data = await res.json();
  return data;
});

/** 建立长连接(握手):调调度接口拿"该连哪个节点"(服务端最少连接决策),照单直连 */
ipcMain.handle('connect:start', async (_e, { token, deviceId }) => {
  if (client) { client.close(); client = null; }

  // 调度接口已由服务端算好最少连接节点,客户端无需感知节点列表
  const dispatchRes = await authFetch(API + '/api/connects');
  const dispatch = await dispatchRes.json();
  const nodeAddr = dispatch.success ? dispatch.address : CONNECT_HOST + ':' + CONNECT_PORT; // 兜底
  const [host, port] = nodeAddr.split(':');
  console.log('[dispatch] 服务端决策节点:', nodeAddr);

  client = new ImClient({
    host,
    port: parseInt(port),
    token,
    deviceId,
    deviceType: 'desktop',
    apiBase: API,
    handlers: {
      onConnected: (userId) => sendToRenderer('conn:status', { status: 'connected', userId }),
      onMessage: (msg) => sendToRenderer('msg:received', msg),
      onAck: (ack) => sendToRenderer('msg:ack', ack),
      onDelivered: (ack) => sendToRenderer('msg:delivered', ack),
      onRead: (read) => sendToRenderer('msg:read', read),
      onGroupRead: (read) => sendToRenderer('msg:group-read', read),
      onClosed: () => sendToRenderer('conn:status', { status: 'closed' }),
      onSendFailed: (msg) => sendToRenderer('msg:failed', msg),
    },
  });
  client.connect();
  return { ok: true };
});

/** 发送消息(单聊:receiverId;群聊:conversationId=群 id + receiverId=群 id) */
ipcMain.handle('chat:send', async (_e, { receiverId, conversationId, content, msgType }) => {
  if (!client) throw new Error('not connected');
  return client.sendMessage({
    receiverId,
    conversationId,   // 群聊时传群 id,connect 按群选队列(群内保序)
    msgType: msgType || 'TEXT',
    content,
    clientTime: Date.now(),
  });
});

/** 上报已读:渲染进程打开会话/看到新消息时,上报"已读到 seq X" */
ipcMain.handle('chat:reportRead', async (_e, { conversationId, untilSeq }) => {
  if (!client) return false;
  return client.reportRead(conversationId, untilSeq);
});

/** 创建群(ownerId 服务端从鉴权上下文取,不信任客户端) */
ipcMain.handle('groups:create', async (_e, { name, members }) => {
  const res = await authFetch(API + '/api/groups', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, members }),
  });
  return res.json();
});

/** 面对面建群:输入 4 位数字,加入/创建该数字窗口的群 */
ipcMain.handle('groups:face2face', async (_e, { code }) => {
  const res = await authFetch(API + '/api/groups/face2face', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
  return res.json();
});

/** 我的群列表(服务端从鉴权上下文取 userId) */
ipcMain.handle('groups:list', async () => {
  const res = await authFetch(API + '/api/groups');
  return res.json();
});

/** 群消息增量拉取(按 seq;limit 控制每页,hasMore 支持翻页) */
ipcMain.handle('groups:pull', async (_e, { groupId, afterSeq, limit }) => {
  const res = await authFetch(`${API}/api/groups/${encodeURIComponent(groupId)}/messages?afterSeq=${afterSeq}${limit ? '&limit=' + limit : ''}`);
  return res.json();
});

/** 断开 */
ipcMain.handle('connect:close', () => {
  if (client) { client.close(); client = null; }
  return { ok: true };
});

/** 当前连接状态 */
ipcMain.handle('conn:status', () => {
  return { connected: client ? client.connected : false, authenticated: client ? client.authenticated : false };
});

function sendToRenderer(channel, data) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, data);
  }
}

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
