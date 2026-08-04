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

let mainWindow = null;
let client = null;

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

/** 登录:调业务层 HTTP 拿 token/deviceId,再建立长连接 */
ipcMain.handle('auth:login', async (_e, { username, password, deviceType }) => {
  const res = await fetch('http://127.0.0.1:8081/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, deviceType: deviceType || 'desktop' }),
  });
  const data = await res.json();
  if (!data.success) throw new Error(data.message || 'login failed');
  return { token: data.token, deviceId: data.deviceId, userId: data.userId, username: data.username, avatarUrl: data.avatarUrl };
});

/** 注册 */
ipcMain.handle('auth:register', async (_e, { username, password }) => {
  const res = await fetch('http://127.0.0.1:8081/api/auth/register', {
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

  const res = await fetch('http://127.0.0.1:8081/api/auth/register/avatar', {
    method: 'POST',
    body: fd,
  });
  const data = await res.json();
  if (!data.success) throw new Error(data.message || 'register failed');
  return data;
});

/** 用户名 → userId 解析(聊天时填用户名,解析成 userId 再发) */
ipcMain.handle('users:resolve', async (_e, { username }) => {
  const res = await fetch(`http://127.0.0.1:8081/api/users/resolve?username=${encodeURIComponent(username)}`);
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

  const res = await fetch(`http://127.0.0.1:8081/api/users/${encodeURIComponent(userId)}/avatar`, {
    method: 'POST',
    body: fd,
  });
  const data = await res.json();
  if (!data.success) throw new Error(data.message || 'upload failed');
  return data;
});

/** 会话列表 */
ipcMain.handle('convs:list', async (_e, { userId }) => {
  const res = await fetch(`http://127.0.0.1:8081/api/conversations?userId=${encodeURIComponent(userId)}`);
  const data = await res.json();
  return data;
});

/** 拉取某会话 afterSeq 之后的消息 */
ipcMain.handle('convs:pull', async (_e, { conversationId, afterSeq }) => {
  const res = await fetch(`http://127.0.0.1:8081/api/conversations/${encodeURIComponent(conversationId)}/messages?afterSeq=${afterSeq}&limit=50`);
  const data = await res.json();
  return data;
});

/** 建立长连接(握手):先调调度接口拿节点列表,选一个直连 */
ipcMain.handle('connect:start', async (_e, { token, deviceId }) => {
  if (client) { client.close(); client = null; }

  // 从调度接口获取可连的 connect 节点
  const dispatchRes = await fetch('http://127.0.0.1:8081/api/connects');
  const dispatch = await dispatchRes.json();
  const nodes = (dispatch.nodes || []).map(n => n.address);
  const nodeAddr = nodes.length > 0
    ? nodes[Math.floor(Math.random() * nodes.length)] // 随机挑一个(负载均衡)
    : '127.0.0.1:9999'; // 兜底
  const [host, port] = nodeAddr.split(':');
  console.log('[dispatch] 选择节点:', nodeAddr);

  client = new ImClient({
    host,
    port: parseInt(port),
    token,
    deviceId,
    deviceType: 'desktop',
    apiBase: 'http://127.0.0.1:8081',
    handlers: {
      onConnected: (userId) => sendToRenderer('conn:status', { status: 'connected', userId }),
      onMessage: (msg) => sendToRenderer('msg:received', msg),
      onAck: (ack) => sendToRenderer('msg:ack', ack),
      onDelivered: (ack) => sendToRenderer('msg:delivered', ack),
      onClosed: () => sendToRenderer('conn:status', { status: 'closed' }),
      onSendFailed: (msg) => sendToRenderer('msg:failed', msg),
    },
  });
  client.connect();
  return { ok: true };
});

/** 发送消息 */
ipcMain.handle('chat:send', async (_e, { receiverId, content, msgType }) => {
  if (!client) throw new Error('not connected');
  return client.sendMessage({
    receiverId,
    msgType: msgType || 'TEXT',
    content,
    clientTime: Date.now(),
  });
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
