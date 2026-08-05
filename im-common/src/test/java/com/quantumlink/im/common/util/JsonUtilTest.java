package com.quantumlink.im.common.util;

import com.quantumlink.im.common.protocol.AckPayload;
import com.quantumlink.im.common.protocol.MessagePayload;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** JSON 工具全覆盖。 */
class JsonUtilTest {

    @Test
    void toJson_roundTrip_object() {
        MessagePayload p = new MessagePayload();
        p.setContent("hello");
        p.setSeq(5L);
        String json = JsonUtil.toJson(p);
        assertTrue(json.contains("\"content\":\"hello\""));
        MessagePayload back = JsonUtil.fromJson(json, MessagePayload.class);
        assertEquals("hello", back.getContent());
        assertEquals(5L, back.getSeq());
    }

    @Test
    void toJson_null_returnsNullLiteral() {
        // Jackson 对 null 返回字符串 "null"(不抛)
        assertEquals("null", JsonUtil.toJson(null));
    }

    @Test
    void fromJson_nullOrEmpty_throws() {
        assertThrows(RuntimeException.class, () -> JsonUtil.fromJson(null, MessagePayload.class));
        assertThrows(RuntimeException.class, () -> JsonUtil.fromJson("", MessagePayload.class));
    }

    @Test
    void fromJson_invalidJson_throws() {
        assertThrows(RuntimeException.class, () -> JsonUtil.fromJson("{invalid", MessagePayload.class));
    }

    @Test
    void toJson_map() {
        Map<String, String> m = new HashMap<>();
        m.put("k", "v");
        String json = JsonUtil.toJson(m);
        assertTrue(json.contains("\"k\":\"v\""));
    }

    @Test
    void toJson_snowflakeLong_stringHandling() {
        AckPayload ack = new AckPayload();
        ack.setServerMsgId("2084954656768843778");
        String json = JsonUtil.toJson(ack);
        // String 下发,不能是裸数字(否则 JS 丢精度)
        assertTrue(json.contains("\"serverMsgId\":\"2084954656768843778\""));
        AckPayload back = JsonUtil.fromJson(json, AckPayload.class);
        assertEquals("2084954656768843778", back.getServerMsgId());
    }
}
