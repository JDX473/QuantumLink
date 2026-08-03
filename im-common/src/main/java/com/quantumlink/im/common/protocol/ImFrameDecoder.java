package com.quantumlink.im.common.protocol;

import com.quantumlink.im.common.ImConstants;
import com.quantumlink.im.common.util.CrcUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * ImFrame 解码器:字节流 → ImFrame,解决粘包拆包。
 *
 * <p>基于 {@link LengthFieldBasedFrameDecoder}:长度字段在帧头的 bodyLen,
 * 位于 offset=6(magic 4 + version 1 + type 1),长度 4 字节。
 * 帧尾还有 crc32(4B),所以 lengthAdjustment=4,把 CRC 归入帧长。
 *
 * <p>流程:
 * <ol>
 *   <li>LengthFieldBasedFrameDecoder 按 bodyLen+crc 切出完整帧;</li>
 *   <li>本解码器校验 magic / version / CRC32;</li>
 *   <li>还原 ImFrame,丢进 pipeline。
 * </ol>
 *
 * <p>粘包拆包原理:lengthFieldOffset=6 告诉解码器"长度字段在字节流第 6 字节",
 * lengthFieldLength=4 表示长度是 4 字节大端;解码器据此算出整帧长度,
 * 等攒够字节数才输出一帧,天然解决粘包半包。
 */
public class ImFrameDecoder extends LengthFieldBasedFrameDecoder {

    public ImFrameDecoder() {
        super(ImConstants.MAX_BODY_LENGTH + ImConstants.HEADER_LENGTH + ImConstants.CRC_LENGTH,
                ImConstants.HEADER_LENGTH - 4, // lengthFieldOffset = 6(magic4+version1+type1)
                4,                              // lengthFieldLength = 4(bodyLen)
                4,                              // lengthAdjustment = 4(crc 归入帧长)
                0);                             // initialBytesToStrip = 0
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null; // 半包,继续等待
        }

        try {
            if (frame.readableBytes() < ImConstants.HEADER_LENGTH + ImConstants.CRC_LENGTH) {
                return null; // 帧不完整(理论上不会,防御)
            }

            // 读帧头
            int magic = frame.readInt();
            byte version = frame.readByte();
            byte typeCode = frame.readByte();
            int bodyLen = frame.readInt();

            if (magic != ImConstants.MAGIC) {
                throw new IllegalArgumentException("bad magic: " + Integer.toHexString(magic));
            }
            if (version != ImConstants.VERSION) {
                throw new IllegalArgumentException("bad version: " + version);
            }

            // 读 body
            byte[] body = new byte[bodyLen];
            frame.readBytes(body);

            // 读 CRC
            int crc = frame.readInt();
            int expect = CrcUtil.crc32(headerBytes(magic, version, typeCode, bodyLen), body);
            if (crc != expect) {
                throw new IllegalArgumentException("crc mismatch: expect=" + expect + " got=" + crc);
            }

            FrameType type = FrameType.fromCode(typeCode);
            return new ImFrame(type, body);
        } finally {
            frame.release();
        }
    }

    private byte[] headerBytes(int magic, byte version, byte typeCode, int bodyLen) {
        byte[] h = new byte[ImConstants.HEADER_LENGTH];
        h[0] = (byte) (magic >>> 24);
        h[1] = (byte) (magic >>> 16);
        h[2] = (byte) (magic >>> 8);
        h[3] = (byte) magic;
        h[4] = version;
        h[5] = typeCode;
        h[6] = (byte) (bodyLen >>> 24);
        h[7] = (byte) (bodyLen >>> 16);
        h[8] = (byte) (bodyLen >>> 8);
        h[9] = (byte) bodyLen;
        return h;
    }
}
