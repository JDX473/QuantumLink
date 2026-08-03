package com.quantumlink.im.common.protocol;

import com.quantumlink.im.common.ImConstants;
import com.quantumlink.im.common.util.CrcUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * ImFrame 编码器:ImFrame → 字节流。
 *
 * <p>线格式:
 * <pre>
 * | magic(4B) | version(1B) | type(1B) | bodyLen(4B) | body(变长) | crc32(4B) |
 * </pre>
 *
 * <p>CRC32 对 header + body 计算,校验帧完整性。
 */
public class ImFrameEncoder extends MessageToByteEncoder<ImFrame> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ImFrame frame, ByteBuf out) {
        byte[] body = frame.getBody();
        int bodyLen = (body == null) ? 0 : body.length;

        // 写帧头(10B)
        out.writeInt(ImConstants.MAGIC);
        out.writeByte(ImConstants.VERSION);
        out.writeByte(frame.getType().code());
        out.writeInt(bodyLen);

        // 写 body
        if (body != null && body.length > 0) {
            out.writeBytes(body);
        }

        // 写 CRC32(对 header+body 计算)
        // 需先算出 header 的字节:magic/version/type/bodyLen 已在 out 中,但从 out 读会移动写指针。
        // 这里直接按相同格式构造 header 字节数组计算 CRC。
        byte[] header = new byte[ImConstants.HEADER_LENGTH];
        header[0] = (byte) (ImConstants.MAGIC >>> 24);
        header[1] = (byte) (ImConstants.MAGIC >>> 16);
        header[2] = (byte) (ImConstants.MAGIC >>> 8);
        header[3] = (byte) ImConstants.MAGIC;
        header[4] = ImConstants.VERSION;
        header[5] = frame.getType().code();
        header[6] = (byte) (bodyLen >>> 24);
        header[7] = (byte) (bodyLen >>> 16);
        header[8] = (byte) (bodyLen >>> 8);
        header[9] = (byte) bodyLen;

        int crc = CrcUtil.crc32(header, body);
        out.writeInt(crc);
    }
}
