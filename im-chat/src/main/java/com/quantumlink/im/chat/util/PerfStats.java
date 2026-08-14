package com.quantumlink.im.chat.util;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消费链阶段耗时统计(压测埋点)。
 *
 * <p>每阶段一个环形缓冲(最近 65536 个样本,覆盖 ~80s@800/s),后台守护线程
 * 每 5s 向 stdout 打一行 {@code PERF|stage:cnt=..,p50=..,p90=..,p99=..,max=..}(ms)。
 * stdout 即 nohup 日志(im-chat-{port}.log),压测时直接看。
 *
 * <p>系统属性 {@code im.perf.stats.enabled=false} 可关闭(生产部署可关);
 * record 单次开销 ≈ 数组写 + 原子自增(纳秒级),对热路径影响可忽略。
 */
public final class PerfStats {

    private static final int CAP = 65536;
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("im.perf.stats.enabled", "true"));
    private static final ConcurrentHashMap<String, long[]> RINGS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> IDX = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService REPORTER;

    static {
        if (ENABLED) {
            REPORTER = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "perf-stats-reporter");
                t.setDaemon(true);
                return t;
            });
            REPORTER.scheduleAtFixedRate(PerfStats::report, 5, 5, TimeUnit.SECONDS);
        } else {
            REPORTER = null;
        }
    }

    private PerfStats() {
    }

    /** 记录一次耗时(ns) */
    public static void record(String stage, long nanos) {
        if (!ENABLED) {
            return;
        }
        long[] ring = RINGS.computeIfAbsent(stage, k -> new long[CAP]);
        AtomicInteger idx = IDX.computeIfAbsent(stage, k -> new AtomicInteger());
        ring[Math.floorMod(idx.getAndIncrement(), CAP)] = nanos;
    }

    /** 记录一次耗时(ms,适合拿不到 nanoTime 的段,如 bornTimestamp 推算) */
    public static void recordMs(String stage, long ms) {
        record(stage, ms * 1_000_000L);
    }

    private static void report() {
        StringBuilder sb = new StringBuilder("PERF");
        for (Map.Entry<String, long[]> e : RINGS.entrySet()) {
            String stage = e.getKey();
            long[] ring = e.getValue();
            int size = Math.min(IDX.get(stage).get(), CAP);
            if (size == 0) {
                continue;
            }
            long[] copy = Arrays.copyOf(ring, size);
            Arrays.sort(copy);
            sb.append(String.format("|%s:cnt=%d,p50=%.1f,p90=%.1f,p99=%.1f,max=%.1f",
                    stage, size,
                    copy[size / 2] / 1e6, copy[size * 9 / 10] / 1e6,
                    copy[size * 99 / 100] / 1e6, copy[size - 1] / 1e6));
        }
        if (sb.length() > 4) {
            System.out.println(sb);
        }
    }
}
