package com.quantumlink.im.logsearch.index;

import lombok.Data;

/**
 * 日志检索请求参数(与 Agent 的 query_logs 契约对齐)。
 *
 * <p>timeFrom/timeTo 可为 epoch 毫秒或 ISO-8601 字符串;不传则全范围。
 * keyword=关键词(在 msg 上做分词检索);regex=正则(在原始行 raw 上匹配);
 * trace_id/conv/level=精确过滤。
 */
@Data
public class SearchQuery {
    private Object timeFrom;
    private Object timeTo;
    private String level;
    private String keyword;
    private String regex;
    private String traceId;
    private String conv;
    private Integer limit;
}
