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

  // 用户解析(用户名 → userId)
  resolveUser: (info) => ipcRenderer.invoke('users:resolve', info),

  // 连接
  connect: (info) => ipcRenderer.invoke('connect:start', info),
  close: () => ipcRenderer.invoke('connect:close'),
  status: () => ipcRenderer.invoke('conn:status'),

  // 消息
  send: (msg) => ipcRenderer.invoke('chat:send', msg),

  // 主进程 → 渲染进程事件
  onConnectionStatus: (cb) => ipcRenderer.on('conn:status', (_e, data) => cb(data)),
  onMessage: (cb) => ipcRenderer.on('msg:received', (_e, data) => cb(data)),
  onAck: (cb) => ipcRenderer.on('msg:ack', (_e, data) => cb(data)),
  onDelivered: (cb) => ipcRenderer.on('msg:delivered', (_e, data) => cb(data)),
  onSendFailed: (cb) => ipcRenderer.on('msg:failed', (_e, data) => cb(data)),
});
