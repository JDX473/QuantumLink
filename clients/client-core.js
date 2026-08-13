/**
 * QuantumLink 客户端核心(Node)。
 *
 * 职责:建连、握手鉴权、心跳、收发消息、断线重连、**发送确认(可靠投递)**。
 * 设计上为多端扩展留接口 —— 不同端(desktop/web/mobile)只提供 device_type,
 * 核心逻辑复用同一份。
 *
 * ## 发送确认机(Phase 2 可靠投递核心)
 *
 * 每条待确认消息的状态机:
 *   SENT →(收到 STORE)→ CONFIRMED(从 pending 移除)
 *     └→(超时/断线)→ RESENDING(重传,次数+1,指数退避)
 *          └→(≥6次)→ FAILED(回调 onSendFailed)
 *
 * 关键点:
 * - 重传带**同一个 clientMsgId**(UUID,全局唯一)→ 服务端幂等去重,不重复落库
 * - 断线时 pending 保留,重连成功后先 flush pending,再发新消息
 * - 收到 ACK-STORE 用 clientMsgId 精确匹配(服务端已在 ACK 里回带)
 */
const net = require('net');
const { randomUUID } = require('crypto');
const { FrameType, encode, FrameDecoder } = require('./protocol');

const HEARTBEAT_INTERVAL_MS = 10000; // 心跳 10s(与服务端一致)
const RECONNECT_BASE_MS = 1000;      // 重连基础间隔
const RECONNECT_MAX_MS = 30000;      // 重连最大间隔
const RECONNECT_MAX_ATTEMPTS = 6;

// ---- 发送确认机参数 ----
const RESEND_TIMEOUT_MS = 3000;      // 首超时:3s 未收 STORE 重传
const RESEND_MAX_ATTEMPTS = 6;       // 最多重传次数
const RESEND_BACKOFF_MAX_MS = 48000; // 退避封顶 48s

class ImClient {
  constructor(opts) {
    this.host = opts.host;
    this.port = opts.port;
    this.token = opts.token;
    this.deviceId = opts.deviceId;
    this.deviceType = opts.deviceType || 'desktop';
    this.handlers = opts.handlers || {};
    // 静默模式:压测/高负载时关闭 console.log,避免日志阻塞 EventLoop 拖慢客户端
    this.quiet = !!opts.quiet;
    this.log = (msg) => { if (!this.quiet) console.log(msg); };

    this.socket = null;
    this.decoder = new FrameDecoder();
    this.heartbeatTimer = null;
    this.reconnectAttempts = 0;
    this.reconnectTimer = null;
    this.intentionalClose = false;
    this.connected = false;
    this.authenticated = false;
    this.everConnected = false; // 首次连接标志,用于区分"重连成功"和"首次连接"

    // 发送确认:clientMsgId → pending 消息
    this.pending = new Map();

    // 增量拉取:conversationId → 已同步的最大 seq(位点)
    this.conversationLastSeq = new Map();
    // 离线补拉 HTTP 接口(im-chat 业务层);可用 IM_API 环境变量覆盖(云部署)
    this.apiBase = opts.apiBase || process.env.IM_API || 'http://8.141.86.246:8081';
  }

  // ==================== 连接生命周期 ====================

  connect() {
    this.intentionalClose = false;
    this.socket = net.connect({ host: this.host, port: this.port }, () => {
      this.connected = true;
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
      this.log(`[client] 握手成功, userId=${body.userId}`);
      if (this.handlers.onConnected) this.handlers.onConnected(body.userId);
      this._startHeartbeat();
      // 判断是否重连:首次连接(everConnected=false)后置 true;重连时 it's already true
      if (this.everConnected) {
        this._flushPending();
        this.reconnectAttempts = 0; // 重连成功,重置退避计数
        this._pullOffline();        // 重连后补拉离线期间的消息
      }
      this.everConnected = true;
    } else {
      console.error(`[client] 握手失败: ${body.reason}`);
      this.close();
    }
  }

  _onMessage(body) {
    // 更新该会话位点(seq 单调递增)
    if (body && body.conversationId && body.seq != null) {
      const last = this.conversationLastSeq.get(body.conversationId) || 0;
      if (body.seq > last) {
        this.conversationLastSeq.set(body.conversationId, body.seq);
      }
    }
    // 收到消息 → 回 DELIVER_ACK(通知服务端"我收到了",服务端转给发送方显示已送达)
    if (body && body.serverMsgId != null) {
      this.sendFrame(FrameType.DELIVER_ACK, {
        ackType: 'DELIVER',
        serverMsgId: body.serverMsgId,
        seq: body.seq,
        conversationId: body.conversationId,
      });
    }
    if (this.handlers.onMessage) this.handlers.onMessage(body);
  }

  /** 收到 ACK(STORE/DELIVER):STORE 用 clientMsgId 匹配 pending;DELIVER 单独回调 */
  _onAck(ack) {
    if (ack && ack.ackType === 'STORE' && ack.clientMsgId) {
      const item = this.pending.get(ack.clientMsgId);
      if (item) {
        this.pending.delete(ack.clientMsgId);
        if (item.timer) clearTimeout(item.timer);
        this.log(`[client] 消息确认: clientMsgId=${ack.clientMsgId} serverMsgId=${ack.serverMsgId} seq=${ack.seq}`);
        if (this.handlers.onAck) this.handlers.onAck(ack);
        return;
      }
      // pending 里没有 → 可能是重复 ACK,忽略
    }
    // DELIVER(对方已送达):单独回调,上层可显示"已送达/已读"
    if (ack && ack.ackType === 'DELIVER' && this.handlers.onDelivered) {
      this.handlers.onDelivered(ack);
      return;
    }
    // READ(对方已读水位推进):不是本客户端发的消息的回执,是对端读了我的消息的事件。
    // 特征:没有 ackType,而是 {conversationId, readerId, untilSeq}(ReadReportPayload)
    if (ack && ack.readerId != null && ack.untilSeq != null && this.handlers.onRead) {
      this.handlers.onRead(ack);
      return;
    }
    // 群已读计数更新:成员读了我在群里的消息,实时更新"n人已读"(只推给发送者,非群广播)
    // 特征:{conversationId, seq, readCount}(无 readerId/untilSeq,与单聊 READ 区分)
    if (ack && ack.readCount != null && ack.seq != null && ack.conversationId != null && this.handlers.onGroupRead) {
      this.handlers.onGroupRead(ack);
      return;
    }
    if (this.handlers.onAck) this.handlers.onAck(ack);
  }

  // ==================== 发送确认机(可靠投递) ====================

  /**
   * 发送消息并登记 pending,启动超时重传。
   * @param {Object} msg 消息内容(receiverId/content/msgType/...)
   * @returns {string} clientMsgId
   */
  sendMessage(msg) {
    // 生成幂等键:UUID 全局唯一(碰撞概率 10^-19 量级,工程上可忽略)。
    // 同一条逻辑消息重传不换号(存 pending 后取同一个 UUID);重启/多设备/多会话都不撞,
    // 无需维护 deviceId + sessionNonce + clientSeq 拼接状态(那是"无状态随机"的绕路)。
    const clientMsgId = randomUUID();
    const full = { ...msg, clientMsgId };

    // 登记 pending
    const item = {
      clientMsgId,
      payload: full,
      attempts: 0,
      timer: null,
    };
    this.pending.set(clientMsgId, item);

    this._sendWithRetry(item);
    return clientMsgId;
  }

  /**
   * 上报已读:本端在会话里看到的最大的 seq。
   * 打开会话/会话中收到新消息渲染后调用。服务端推进本端水位并推 READ 事件给对端。
   * @param {string} conversationId 会话 ID
   * @param {number} untilSeq 已读到的最大 seq(≤ 该值全部视为已读)
   */
  reportRead(conversationId, untilSeq) {
    if (!this.connected || !conversationId || !untilSeq) return false;
    return this.sendFrame(FrameType.READ_ACK, { conversationId, untilSeq });
  }

  /** 发送一条 pending 消息(首次 or 重传),并安排下一次重传 */
  _sendWithRetry(item) {
    if (!this.connected) {
      // 未连接:pending 保留,重连成功后 flush
      this.log(`[client] 未连接,消息排队待发: ${item.clientMsgId}`);
      return;
    }
    const ok = this.sendFrame(FrameType.MSG, item.payload);
    if (ok) {
      this.log(`[client] 发送(第${item.attempts + 1}次): ${item.clientMsgId}`);
    }

    // 安排重传定时器
    if (item.timer) clearTimeout(item.timer);
    const delay = this._backoffDelay(item.attempts);
    item.timer = setTimeout(() => this._onResendTimeout(item), delay);
  }

  /** 定时器到:未收到 STORE → 重传(指数退避) */
  _onResendTimeout(item) {
    item.timer = null;

    // 已被确认(重复 ACK 竞态)则不再重传
    if (!this.pending.has(item.clientMsgId)) return;

    item.attempts++;
    if (item.attempts >= RESEND_MAX_ATTEMPTS) {
      this.pending.delete(item.clientMsgId);
      console.error(`[client] 发送失败(超过${RESEND_MAX_ATTEMPTS}次): ${item.clientMsgId}`);
      if (this.handlers.onSendFailed) this.handlers.onSendFailed(item.payload);
      return;
    }
    this._sendWithRetry(item);
  }

  /** 指数退避 + 抖动:3s→6s→12s→24s→48s→48s */
  _backoffDelay(attempts) {
    const base = RESEND_TIMEOUT_MS * Math.pow(2, attempts);
    const capped = Math.min(base, RESEND_BACKOFF_MAX_MS);
    // ±20% 抖动,防重传风暴(一批客户端同时超时同时重发)
    const jitter = capped * 0.2 * (Math.random() * 2 - 1);
    return Math.max(0, capped + jitter);
  }

  /** 重连成功后 flush pending:先重传积压消息(它们还没被确认) */
  _flushPending() {
    for (const item of this.pending.values()) {
      item.attempts = 0; // 重置重试计数(新连接)
      this._sendWithRetry(item);
    }
    if (this.pending.size > 0) {
      console.log(`[client] 重连成功,重传 ${this.pending.size} 条待确认消息`);
    }
  }

  // ==================== 离线消息 + 增量拉取 ====================

  /**
   * 补拉离线消息:按会话位点(afterSeq)调 HTTP 接口,拉取之后的消息。
   * 用于重连后/上线时,把离线期间的消息补回来。
   */
  async _pullOffline() {
    // 需要知道有哪些会话 → 客户端本地维护的会话位点表里有
    for (const [conversationId, lastSeq] of this.conversationLastSeq) {
      await this._pullConversation(conversationId, lastSeq);
    }
  }

  /** 拉取某个会话 afterSeq 之后的消息,支持分页 */
  async _pullConversation(conversationId, afterSeq) {
    try {
      const url = `${this.apiBase}/api/conversations/${encodeURIComponent(conversationId)}/messages?afterSeq=${afterSeq}&limit=50`;
      // 拉历史接口需鉴权(与桌面端 main.js 的 authFetch 一致);不加会被 401 拒绝
      const res = await fetch(url, { headers: { Authorization: 'Bearer ' + this.token } });
      if (!res.ok) {
        console.error(`[client] 拉取失败: HTTP ${res.status}`);
        return;
      }
      const data = await res.json();
      for (const item of (data.messages || [])) {
        if (this.handlers.onMessage) this.handlers.onMessage(item);
        // 更新位点
        if (item.seq > (this.conversationLastSeq.get(conversationId) || 0)) {
          this.conversationLastSeq.set(conversationId, item.seq);
        }
      }
      if (data.messages && data.messages.length > 0) {
        this.log(`[client] 增量拉取 ${conversationId}: ${data.messages.length} 条(afterSeq=${afterSeq}, serverMax=${data.serverMaxSeq})`);
      }
      // 有更多则继续拉
      if (data.hasMore) {
        await this._pullConversation(conversationId, data.messages[data.messages.length - 1].seq);
      }
    } catch (e) {
      console.error(`[client] 拉取异常: ${e.message}`);
    }
  }

  // ==================== 心跳 / 重连 / 关闭 ====================

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

  _onDisconnected() {
    this.connected = false;
    this.authenticated = false;
    this._stopHeartbeat();
    if (this.handlers.onClosed) this.handlers.onClosed();

    // pending 保留不清空,重连成功后 flush
    if (this.intentionalClose) return;

    if (this.reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
      console.error('[client] 重连次数达到上限,放弃');
      return;
    }

    const delay = Math.min(
      RECONNECT_BASE_MS * Math.pow(2, this.reconnectAttempts),
      RECONNECT_MAX_MS
    ) + Math.random() * 1000;
    this.reconnectAttempts++;
    console.log(`[client] ${delay}ms 后第 ${this.reconnectAttempts} 次重连...`);

    this.reconnectTimer = setTimeout(() => this.connect(), delay);
  }

  close() {
    this.intentionalClose = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this._stopHeartbeat();
    if (this.socket) this.socket.destroy();
  }
}

module.exports = { ImClient, HEARTBEAT_INTERVAL_MS, RESEND_TIMEOUT_MS, RESEND_MAX_ATTEMPTS };
