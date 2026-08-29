package com.quantumlink.im.logsearch;

import com.quantumlink.im.logsearch.index.LogIndexService;
import com.quantumlink.im.logsearch.index.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 日志查询平台核心单测:灌日志 → 建索引 → 按各维度查询 → 断言。 */
class LogIndexServiceTest {

    @TempDir
    Path tmp;

    static final String STORE =
            "2026-08-13T19:59:27.728+08:00  INFO 47076 --- [im-chat] [pool-5-thread-1] "
                    + "c.q.im.chat.service.MessageService : message stored: msgId=2087871653865947140 "
                    + "conv=u_a#u_b seq=20 sender=u_a";
    static final String ACK =
            "2026-08-13T19:59:27.730+08:00  INFO 47076 --- [im-chat] [ool-5-thread-13] "
                    + "c.q.im.chat.service.MessageService : ACK-STORE sent: sender=u_a "
                    + "clientMsgId=38b5615f-7d18-49ae-bc55-5b91857849d7 serverMsgId=2087871653865947140 seq=20";
    static final String WARN =
            "2026-08-13T19:59:28.100+08:00  WARN 47076 --- [im-chat] [pool-5-thread-2] "
                    + "c.q.im.chat.service.MessageService : fill sender profile failed: sender=u_x";
    static final String CONNECT_NO_TS =
            "[main] INFO com.quantumlink.im.connect.consumer.DownstreamConsumer - downstream consumer started";

    private LogIndexService svc(String... lines) throws Exception {
        Path logDir = tmp.resolve("logs-" + System.nanoTime());
        Files.createDirectories(logDir);
        Files.writeString(logDir.resolve("chat.log"), String.join("\n", lines) + "\n");
        LogIndexService s = new LogIndexService(tmp.resolve("idx-" + System.nanoTime()),
                List.of(logDir), ".*\\.log");
        s.init();
        return s;
    }

    private long total(LogIndexService s, SearchQuery q) throws Exception {
        return ((Number) s.search(q).get("total")).longValue();
    }

    @Test
    void indexesOnlyLinesWithTimestamp() throws Exception {
        LogIndexService s = svc(STORE, CONNECT_NO_TS);
        assertEquals(1, total(s, new SearchQuery())); // 无时间戳的 connect 行被跳过
    }

    @Test
    void queriesByTimeRange() throws Exception {
        LogIndexService s = svc(STORE, ACK, WARN);
        SearchQuery q = new SearchQuery();
        q.setTimeFrom("2026-08-13T19:59:27.700+08:00");
        q.setTimeTo("2026-08-13T19:59:27.800+08:00");
        assertEquals(2, total(s, q)); // STORE+ACK 在内,WARN 在外
    }

    @Test
    void queriesByTraceId() throws Exception {
        LogIndexService s = svc(STORE, ACK, WARN);
        SearchQuery q = new SearchQuery();
        q.setTraceId("2087871653865947140");
        assertEquals(2, total(s, q)); // 同一消息的 store+ack 可被 trace_id 归并
    }

    @Test
    void queriesByKeywordAndLevel() throws Exception {
        LogIndexService s = svc(STORE, ACK, WARN);
        SearchQuery q = new SearchQuery();
        q.setKeyword("message stored");
        assertEquals(1, total(s, q));
        q = new SearchQuery();
        q.setLevel("WARN");
        assertEquals(1, total(s, q));
    }

    @Test
    void queriesByRegexOnRawLine() throws Exception {
        LogIndexService s = svc(STORE, ACK, WARN);
        SearchQuery q = new SearchQuery();
        q.setRegex(".*2087871653865947140.*");
        assertEquals(2, total(s, q));
    }

    @Test
    void refreshSeesNewAppendedLines() throws Exception {
        Path logDir = tmp.resolve("logs-app");
        Files.createDirectories(logDir);
        Path log = logDir.resolve("chat.log");
        Files.writeString(log, STORE + "\n");
        LogIndexService s = new LogIndexService(tmp.resolve("idx-app"), List.of(logDir), ".*\\.log");
        s.init();
        assertEquals(1, total(s, new SearchQuery()));
        Files.writeString(log, STORE + "\n" + WARN + "\n"); // IM 继续写新日志
        assertEquals(2, total(s, new SearchQuery())); // 下次查询自动刷新,能看到新行
    }

    @Test
    void hitsCarryStructuredFields() throws Exception {
        LogIndexService s = svc(STORE, ACK);
        SearchQuery q = new SearchQuery();
        q.setTraceId("2087871653865947140");
        Map<String, Object> r = s.search(q);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) r.get("hits");
        assertEquals(2, hits.size());
        Map<String, Object> first = hits.get(0);
        assertEquals("INFO", first.get("level"));
        assertEquals("2087871653865947140", first.get("trace_id"));
        // ACK 行无 conv;STORE 行有 conv=u_a#u_b——断言至少一条命中带该 conv
        boolean anyConv = hits.stream().anyMatch(h -> "u_a#u_b".equals(h.get("conv")));
        assertEquals(true, anyConv);
        assertEquals("INFO", first.get("level"));
    }

    // ------------------------------------------------------------------
    // 多行异常折叠:ERROR/WARN 后的异常栈并入该条目,让"根因 cause"可被检索
    // ------------------------------------------------------------------
    static final String ERR =
            "2026-08-29T10:00:00.000+08:00 ERROR 111 --- [im-chat] [scheduling-1] "
                    + "c.q.im.chat.service.OutboxService : outbox scan error";
    static final String ERR_FRAME =
            "\tat io.lettuce.core.internal.ExceptionFactory.createExecutionException";
    static final String ERR_CAUSE =
            "Caused by: io.lettuce.core.RedisCommandExecutionException: ERR unknown command 'ZPOPMIN'";

    @Test
    void foldsExceptionStackIntoErrorEntry() throws Exception {
        LogIndexService s = svc(ERR, "", ERR_FRAME, ERR_CAUSE);
        // 折叠后仍只有 1 份文档(异常栈不是独立文档)
        assertEquals(1, total(s, new SearchQuery()));
        // 根因 cause 可被关键词检索(在 msg 全文里)
        SearchQuery kw = new SearchQuery();
        kw.setKeyword("ZPOPMIN");
        assertEquals(1, total(s, kw));
        // 根因 cause 可被正则检索(在 raw 原文里)
        SearchQuery rg = new SearchQuery();
        rg.setRegex(".*ZPOPMIN.*");
        assertEquals(1, total(s, rg));
    }

    @Test
    void foldedDocCarriesFullStack() throws Exception {
        LogIndexService s = svc(ERR, ERR_FRAME, ERR_CAUSE);
        SearchQuery q = new SearchQuery();
        q.setLevel("ERROR");
        Map<String, Object> r = s.search(q);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) r.get("hits");
        assertEquals(1, hits.size());
        String raw = (String) hits.get(0).get("raw");
        assertTrue(raw.contains("ERR unknown command 'ZPOPMIN'"));
        assertTrue(raw.contains("at io.lettuce"));
    }

    @Test
    void doesNotFoldPerfAfterInfo() throws Exception {
        // INFO 后的无时间戳指标行(PERF)不应被折叠进条目,doc 数与原文保持对应
        String info = "2026-08-29T10:00:00.000+08:00  INFO 111 --- [im-chat] [thread-1] "
                + "c.q.im.chat.mq.DownstreamProducer : downstream sent: topic=server2client";
        LogIndexService s = svc(info, "PERF|mq_up:cnt=1,p50=1.0", "PERF|mq_up:cnt=2,p50=2.0");
        assertEquals(1, total(s, new SearchQuery()));
        SearchQuery rg = new SearchQuery();
        rg.setRegex(".*PERF.*");
        assertEquals(0, total(s, rg));
    }
}
