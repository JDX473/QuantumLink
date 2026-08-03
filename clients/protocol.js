/**
 * QuantumLink 自定义 TCP 协议编解码(Node 版,与 im-common Java 协议一致)。
 *
 * 帧格式:
 *   | magic(4B) | version(1B) | type(1B) | bodyLen(4B) | body(变长 JSON) | crc32(4B) |
 *
 * 粘包拆包:按 bodyLen 累积分帧,攒够一整帧才回调。CRC32 校验帧完整性。
 */

const MAGIC = 0x514e4c43; // "QNLC"
const VERSION = 1;
const HEADER_LENGTH = 10;
const CRC_LENGTH = 4;

// 帧类型(与 FrameType.java 一致)
const FrameType = {
  HANDSHAKE: 1,
  HANDSHAKE_ACK: 2,
  MSG: 3,
  MSG_ACK: 4,
  PING: 5,
  PONG: 6,
  ERROR: 7,
  DELIVER_ACK: 8,
};

function crc32(buf) {
  // CRC32 查表法(Node 无内置,用标准实现)
  let table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      }
      table[n] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) {
    crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xff];
  }
  return (crc ^ -1) >>> 0;
}

/** 编码一帧:type + body(JSON 对象或 Buffer) → Buffer */
function encode(type, body) {
  const bodyBuf = Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body || {}), 'utf8');
  const bodyLen = bodyBuf.length;
  const header = Buffer.alloc(HEADER_LENGTH);
  header.writeUInt32BE(MAGIC, 0);
  header.writeUInt8(VERSION, 4);
  header.writeUInt8(type, 5);
  header.writeUInt32BE(bodyLen, 6);

  const frame = Buffer.alloc(HEADER_LENGTH + bodyLen + CRC_LENGTH);
  header.copy(frame, 0);
  bodyBuf.copy(frame, HEADER_LENGTH);
  const crc = crc32(Buffer.concat([header, bodyBuf]));
  frame.writeUInt32BE(crc, HEADER_LENGTH + bodyLen);
  return frame;
}

/** 解码器:维护接收缓冲,粘包拆包,每完整一帧回调 */
class FrameDecoder {
  constructor() {
    this.buffer = Buffer.alloc(0);
  }

  push(data, onFrame) {
    this.buffer = this.buffer.length === 0 ? data : Buffer.concat([this.buffer, data]);
    while (this.buffer.length >= HEADER_LENGTH + CRC_LENGTH) {
      const bodyLen = this.buffer.readUInt32BE(6);
      const totalLen = HEADER_LENGTH + bodyLen + CRC_LENGTH;
      if (this.buffer.length < totalLen) break; // 半包,等待
      const frame = this.buffer.subarray(0, totalLen);
      this.buffer = this.buffer.subarray(totalLen);

      const magic = frame.readUInt32BE(0);
      if (magic !== MAGIC) throw new Error(`bad magic: 0x${magic.toString(16)}`);
      const type = frame.readUInt8(5);
      const bodyBuf = frame.subarray(HEADER_LENGTH, HEADER_LENGTH + bodyLen);
      const expectedCrc = frame.readUInt32BE(HEADER_LENGTH + bodyLen);
      const actualCrc = crc32(frame.subarray(0, HEADER_LENGTH + bodyLen));
      if (expectedCrc !== actualCrc) throw new Error('crc mismatch');

      let body = {};
      if (bodyBuf.length > 0) {
        try { body = JSON.parse(bodyBuf.toString('utf8')); } catch (e) { body = {}; }
      }
      onFrame({ type, body });
    }
  }
}

module.exports = { FrameType, encode, FrameDecoder };
