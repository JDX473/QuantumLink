package com.quantumlink.im.common.util;

/**
 * 会话 ID 工具。
 *
 * <p>conversationId = min(a,b)#max(a,b):保证同一对用户 A、B 的会话 ID 稳定,
 * 不管谁发起(A→B 和 B→A 是同一个会话)。connect(选队列)和 chat(落库)共用。
 */
public final class ConversationIdUtil {
    private ConversationIdUtil() {}

    /** 构建会话 ID */
    public static String build(String a, String b) {
        if (a == null || b == null) {
            return a + "#" + b;
        }
        int cmp = a.compareTo(b);
        return cmp <= 0 ? a + "#" + b : b + "#" + a;
    }
}
