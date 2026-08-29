package com.quantumlink.im.logsearch.api;

import com.quantumlink.im.logsearch.index.LogIndexService;
import com.quantumlink.im.logsearch.index.SearchQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 日志查询平台 HTTP API(Agent 的 query_logs 调这个,不再直接扫文件)。
 *
 * <pre>POST /api/v1/logs/search
 * { "timeFrom": "2026-08-13T19:59:00+08:00", "timeTo": 1723540000000,
 *   "level": "WARN", "keyword": "message stored",
 *   "regex": "serverMsgId=2087871653865947140.*", "traceId": "...", "conv": "...", "limit": 50 }
 * → { "total": n, "levels": {"INFO": n, ...}, "hits": [{ts,level,logger,thread,trace_id,conv,msg,raw}, ...] }</pre>
 */
@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class LogSearchController {

    private final LogIndexService indexService;

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody(required = false) SearchQuery query) throws Exception {
        return indexService.search(query == null ? new SearchQuery() : query);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "up", "service", "im-logsearch");
    }
}
