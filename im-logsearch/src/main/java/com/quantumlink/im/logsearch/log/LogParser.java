package com.quantumlink.im.logsearch.log;

import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志行解析器。两种格式:
 * <ol>
 *   <li>chat 主格式(Spring Boot 默认输出,含时间戳):
 *       {@code 2026-08-13T19:59:27.730+08:00  INFO 47076 --- [im-chat] [thread] logger : msg}</li>
 *   <li>connect 格式(无时间戳):{@code [thread] INFO logger - msg} —— 无法按时间检索,不索引</li>
 * </ol>
 * 业务字段(trace_id/conv)从 msg 的 key=value 提取,供平台派生归因主键。
 */
public final class LogParser {

    /** chat 主格式(时间戳 + 级别 + pid + --- [app] [thread] logger : msg) */
    private static final Pattern MAIN = Pattern.compile(
            "^(?<ts>\\S+)\\s+(?<level>\\w+)\\s+\\d+\\s+---\\s+"
                    + "\\[(?<app>[^\\]]+)\\]\\s+\\[(?<thread>[^\\]]+)\\]\\s+"
                    + "(?<logger>\\S+)\\s*:\\s?(?<msg>.*)$");

    private static final Pattern SERVER_MSG = Pattern.compile("serverMsgId=([0-9]+)");
    private static final Pattern MSG_ID = Pattern.compile("(?:^|[^a-zA-Z])msgId=([0-9A-Za-z]+)");
    private static final Pattern CLIENT_MSG = Pattern.compile("clientMsgId=([0-9a-f-]+)");
    private static final Pattern CONV = Pattern.compile("(?:conv|group)=([^\\s,]+)");

    private LogParser() {
    }

    /** 解析一行;返回 null 表示无法解析或缺失时间戳(connect 格式),不进入索引。 */
    public static LogEntry parse(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        Matcher m = MAIN.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String tsStr = m.group("ts");
        Long tsMillis;
        try {
            tsMillis = OffsetDateTime.parse(tsStr).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null; // 时间戳不可解析 → 无法按时间检索,跳过
        }
        String msg = m.group("msg");

        LogEntry e = new LogEntry();
        e.setTsMillis(tsMillis);
        e.setTs(tsStr);
        e.setLevel(m.group("level"));
        e.setLogger(m.group("logger"));
        e.setThread(m.group("thread"));
        e.setMsg(msg);
        e.setRaw(line.trim());

        Matcher sm = SERVER_MSG.matcher(msg);
        Matcher im = MSG_ID.matcher(msg);
        Matcher cm = CLIENT_MSG.matcher(msg);
        e.setTraceId(sm.find() ? sm.group(1)
                : im.find() ? im.group(1)
                : cm.find() ? cm.group(1)
                : null);
        Matcher cv = CONV.matcher(msg);
        e.setConv(cv.find() ? cv.group(1) : null);
        return e;
    }
}
