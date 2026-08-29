package com.quantumlink.im.logsearch.log;

import lombok.Data;

/**
 * 一条解析后的结构化日志事件。
 *
 * <p>tsMillis 为空 = 该行无时间戳(如 connect 的 [thread] INFO ... 格式),不会被索引。
 * traceId 由平台从消息字段派生(serverMsgId &gt; msgId &gt; clientMsgId),作为跨环节归因主键。
 */
@Data
public class LogEntry {
    private Long tsMillis;
    private String ts;
    private String level;
    private String logger;
    private String thread;
    private String traceId;
    private String conv;
    private String msg;
    private String raw;
}
