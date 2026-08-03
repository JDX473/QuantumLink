/**
 * QuantumLink 客户端核心(Node)。
 *
 * 职责:建连、握手鉴权、心跳、收发消息、断线重连。
 * 设计上为多端扩展留接口 —— 不同端(desktop/web/mobile)只提供 device_type,
 * 核心逻辑复用同一份。
 */
const net = require('net');
const { FrameType, encode, FrameDecoder } = require('./protocol');

const HEARTBEAT_INTERVAL_MS = 10000; // 心跳 10s(与服务端一致)
const RECONNECT_BASE_MS = 1000;      // 重连基础间隔
const RECONNECT_MAX_MS = 30000;      // 重连最大间隔
const RECONNECT_MAX_ATTEMPTS = 6;

class ImClient {
  /**
   * @param {Object} opts
   * @param {string} opts.host 服务端地址
   * @param {number} opts.port 服务端端口
   * @param {string} opts.token 登录 token
   * @param {string} opts.deviceId 设备 ID(服务端分配)
   * @param {string} opts.deviceType 端类型: desktop / web / mobile
   * @param {Object} opts.handlers { onConnected, onMessage, onAck, onError, onClosed }
   */
  constructor(opts) {
    this.host = opts.host;
    this.port = opts.port;
    this.token = opts.token;
    this.deviceId = opts.deviceId;
    this.deviceType = opts.deviceType || 'desktop';
    this.handlers = opts.handlers || {};

    this.socket = null;
    this.decoder = new FrameDecoder();
    this.heartbeatTimer = null;
    this.reconnectAttempts = 0;
    this.reconnectTimer = null;
    this.intentionalClose = false;
    this.connected = false;
    this.authenticated = false;
  }

  /** 建立连接并触发握手 */
  connect() {
    this.intentionalClose = false;
    this.socket = net.connect({ host: this.host, port: this.port }, () => {
      this.connected = true;
      this.reconnectAttempts = 0;
      this._handshake();
    });

    this.socket.on('data', (data) => {
      try {
        this.decoder.push(data, (frame) => this._onFrame(frame));
      } catch (e) {
        console.error('[client] decode error:', e.message);
        this.close();
      }
    });

    this.socket.on('close', () => this._onDisconnected());
    this.socket.on('error', (e) => {
      console.error('[client] socket error:', e.message);
    });
  }

  _handshake() {
    this.sendFrame(FrameType.HANDSHAKE, {
      token: this.token,
      deviceId: this.deviceId,
    });
  }

  _onFrame(frame) {
    switch (frame.type) {
      case FrameType.HANDSHAKE_ACK:
        this._onHandshakeAck(frame.body);
        break;
      case FrameType.MSG:
        this._onMessage(frame.body);
        break;
      case FrameType.MSG_ACK:
        this._onAck(frame.body);
        break;
      case FrameType.PONG:
        // 心跳应答,忽略
        break;
      case FrameType.ERROR:
        console.error('[client] server error:', frame.body);
        if (this.handlers.onError) this.handlers.onError(frame.body);
        break;
      default:
        console.warn('[client] unexpected frame type:', frame.type);
    }
  }

  _onHandshakeAck(body) {
    if (body.success) {
      this.authenticated = true;
      console.log(`[client] 握手成功, userId=${body.userId}`);
      if (this.handlers.onConnected) this.handlers.onConnected(body.userId);
      this._startHeartbeat();
    } else {
      console.error(`[client] 握手失败: ${body.reason}`);
      this.close();
    }
  }

  _onMessage(body) {
    if (this.handlers.onMessage) this.handlers.onMessage(body);
  }

  _onAck(body) {
    if (this.handlers.onAck) this.handlers.onAck(body);
  }

  /** 发送消息(业务层) */
  sendMessage(msg) {
    this.sendFrame(FrameType.MSG, msg);
  }

  /** 发送原始帧 */
  sendFrame(type, body) {
    if (!this.socket || !this.connected) {
      console.error('[client] not connected');
      return false;
    }
    this.socket.write(encode(type, body));
    return true;
  }

  _startHeartbeat() {
    this._stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      this.sendFrame(FrameType.PING, {});
    }, HEARTBEAT_INTERVAL_MS);
  }

  _stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  /** 断线 → 指数退避重连(带抖动,防重连风暴) */
  _onDisconnected() {
    this.connected = false;
    this.authenticated = false;
    this._stopHeartbeat();
    if (this.handlers.onClosed) this.handlers.onClosed();

    if (this.intentionalClose) return;

    if (this.reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
      console.error('[client] 重连次数达到上限,放弃');
      return;
    }

    const delay = Math.min(
      RECONNECT_BASE_MS * Math.pow(2, this.reconnectAttempts),
      RECONNECT_MAX_MS
    ) + Math.random() * 1000; // 抖动 ±1s
    this.reconnectAttempts++;
    console.log(`[client] ${delay}ms 后第 ${this.reconnectAttempts} 次重连...`);

    this.reconnectTimer = setTimeout(() => this.connect(), delay);
  }

  /** 主动关闭(不再重连) */
  close() {
    this.intentionalClose = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this._stopHeartbeat();
    if (this.socket) this.socket.destroy();
  }
}

module.exports = { ImClient, HEARTBEAT_INTERVAL_MS };
