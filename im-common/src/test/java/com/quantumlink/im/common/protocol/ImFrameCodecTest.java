package com.quantumlink.im.common.protocol;

import com.quantumlink.im.common.util.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协议编解码测试:验证 ImFrameEncoder / ImFrameDecoder 正确工作。
 *
 * <p>EmbeddedChannel 把 encoder 和 decoder 串成一个管道,
 * writeOutbound 走编码器、readInbound 走解码器,验证编解码往返一致。
 */
class ImFrameCodecTest {

    @Test
    void roundTrip_singleFrame() {
        EmbeddedChannel ch = new EmbeddedChannel(new ImFrameEncoder(), new ImFrameDecoder());

        MessagePayload payload = new MessagePayload();
        payload.setClientMsgId("D-1-1");
        payload.setConversationId("u1#u2");
        payload.setSenderId("u1");
        payload.setReceiverId("u2");
        payload.setContent("hello");
        payload.setMsgType("TEXT");

        ImFrame out = ProtocolUtil.buildFrame(FrameType.MSG, payload);
        ch.writeOutbound(out);

        ByteBuf encoded = ch.readOutbound();
        assertNotNull(encoded);

        ch.writeInbound(encoded);
        ImFrame in = ch.readInbound();
        assertNotNull(in);
        assertEquals(FrameType.MSG, in.getType());

        MessagePayload decoded = ProtocolUtil.parseBody(in, MessagePayload.class);
        assertNotNull(decoded);
        assertEquals("u1", decoded.getSenderId());
        assertEquals("u2", decoded.getReceiverId());
        assertEquals("hello", decoded.getContent());
        assertEquals("D-1-1", decoded.getClientMsgId());
        assertEquals("u1#u2", decoded.getConversationId());
    }

    @Test
    void twoFramesInOnePacket_stickyPacket() {
        // 粘包:两个帧拼在同一个 ByteBuf 里,解码器应拆出两帧
        EmbeddedChannel ch = new EmbeddedChannel(new ImFrameEncoder(), new ImFrameDecoder());

        MessagePayload p1 = new MessagePayload();
        p1.setClientMsgId("D-1-1");
        p1.setSenderId("u1");
        p1.setContent("first");
        ImFrame f1 = ProtocolUtil.buildFrame(FrameType.MSG, p1);

        MessagePayload p2 = new MessagePayload();
        p2.setClientMsgId("D-1-2");
        p2.setSenderId("u1");
        p2.setContent("second");
        ImFrame f2 = ProtocolUtil.buildFrame(FrameType.MSG, p2);

        ByteBuf buf = Unpooled.buffer();
        ch.writeOutbound(f1);
        buf.writeBytes((ByteBuf) ch.readOutbound());
        ch.writeOutbound(f2);
        buf.writeBytes((ByteBuf) ch.readOutbound());

        ch.writeInbound(buf);
        ImFrame in1 = ch.readInbound();
        ImFrame in2 = ch.readInbound();

        assertNotNull(in1);
        assertNotNull(in2);
        MessagePayload d1 = ProtocolUtil.parseBody(in1, MessagePayload.class);
        MessagePayload d2 = ProtocolUtil.parseBody(in2, MessagePayload.class);
        assertEquals("first", d1.getContent());
        assertEquals("second", d2.getContent());
    }

    @Test
    void halfPacket_thenRest() {
        // 半包:先写入半个帧,再写剩下的,解码器应等到完整才输出
        EmbeddedChannel ch = new EmbeddedChannel(new ImFrameEncoder(), new ImFrameDecoder());

        MessagePayload payload = new MessagePayload();
        payload.setClientMsgId("D-1-3");
        payload.setSenderId("u1");
        payload.setContent("half-packet-test");
        ImFrame out = ProtocolUtil.buildFrame(FrameType.MSG, payload);

        ByteBuf full = Unpooled.buffer();
        ch.writeOutbound(out);
        full.writeBytes((ByteBuf) ch.readOutbound());

        int mid = full.readableBytes() / 2;
        ByteBuf first = full.readBytes(mid);
        ByteBuf second = full.readBytes(full.readableBytes());

        ch.writeInbound(first);
        assertNull(ch.readInbound(), "半包不应产生帧");

        ch.writeInbound(second);
        ImFrame in = ch.readInbound();
        assertNotNull(in);
        MessagePayload decoded = ProtocolUtil.parseBody(in, MessagePayload.class);
        assertEquals("half-packet-test", decoded.getContent());
    }
}
