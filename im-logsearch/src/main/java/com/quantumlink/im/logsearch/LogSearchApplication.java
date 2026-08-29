package com.quantumlink.im.logsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 日志查询平台(独立服务,内嵌 Lucene 索引)。
 *
 * <p>职责:把 IM 打出的日志文件 ingest 进 Lucene 全文索引,对外暴露结构化查询 API
 * (时间范围/级别/关键词/正则/trace_id/conv)。Agent 通过该 API 查日志,不再直接扫文件。
 */
@SpringBootApplication
public class LogSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogSearchApplication.class, args);
    }
}
