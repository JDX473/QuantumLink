/**
 * QuantumLink 桌面客户端 - preload
 *
 * 通过 contextBridge 把主进程的 TCP 客户端能力安全暴露给渲染进程(UI)。
 * 渲染进程只通过 window.quantumlink 调用,不直接接触 Node/TCP。
 */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('quantumlink', {
  // 认证
  login: (creds) => ipcRenderer.invoke('auth:login', creds),
  register: (creds) => ipcRenderer.invoke('auth:register', creds),
  registerWithAvatar: (creds) => ipcRenderer.invoke('auth:register-with-avatar', creds),

  // 用户解析(用户名 → userId)
  resolveUser: (info) => ipcRenderer.invoke('users:resolve', info),
  // 修改头像
  updateAvatar: (info) => ipcRenderer.invoke('users:update-avatar', info),

  // 会话
  listConversations: (info) => ipcRenderer.invoke('convs:list', info),
  pullMessages: (info) => ipcRenderer.invoke('convs:pull', info),

  // 连接
  connect: (info) => ipcRenderer.invoke('connect:start', info),
  close: () => ipcRenderer.invoke('connect:close'),
  status: () => ipcRenderer.invoke('conn:status'),

  // 消息
  send: (msg) => ipcRenderer.invoke('chat:send', msg),
  // 已读上报
  reportRead: (info) => ipcRenderer.invoke('chat:reportRead', info),
  // 群聊
  createGroup: (info) => ipcRenderer.invoke('groups:create', info),
  face2face: (info) => ipcRenderer.invoke('groups:face2face', info),
  listGroups: (info) => ipcRenderer.invoke('groups:list', info),
  pullGroupMessages: (info) => ipcRenderer.invoke('groups:pull', info),

  // 主进程 → 渲染进程事件
  onConnectionStatus: (cb) => ipcRenderer.on('conn:status', (_e, data) => cb(data)),
  onMessage: (cb) => ipcRenderer.on('msg:received', (_e, data) => cb(data)),
  onAck: (cb) => ipcRenderer.on('msg:ack', (_e, data) => cb(data)),
  onDelivered: (cb) => ipcRenderer.on('msg:delivered', (_e, data) => cb(data)),
  onRead: (cb) => ipcRenderer.on('msg:read', (_e, data) => cb(data)),
  onSendFailed: (cb) => ipcRenderer.on('msg:failed', (_e, data) => cb(data)),
});
