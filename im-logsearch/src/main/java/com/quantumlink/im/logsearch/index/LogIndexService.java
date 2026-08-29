package com.quantumlink.im.logsearch.index;

import com.quantumlink.im.logsearch.log.LogEntry;
import com.quantumlink.im.logsearch.log.LogParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 内嵌 Lucene 日志索引服务。
 *
 * <p>索引策略(小规模,单机够用):
 * <ul>
 *   <li>ingest:扫描配置的日志目录 *.log,用 {@link LogParser} 解析为结构化事件;</li>
 *   <li>刷新:按文件指纹(mtime,size)检测变化,变了就重建索引(简单正确,规模上来再改增量);</li>
 *   <li>查询:时间范围为主入口(LongPoint),辅以 level/trace_id/conv 精确过滤、
 *       关键词(QueryParser on msg)、正则(RegexpQuery on raw 原始行)。</li>
 * </ul>
 * 字段:ts(LongPoint+ts_sort)、level/logger/thread/trace_id/conv(StringField)、
 * msg(TextField)、raw(原始行,StringField 供正则)。
 */
@Slf4j
@Component
public class LogIndexService {

    private static final String[] LEVELS = {"INFO", "WARN", "ERROR"};
    /** 单条异常最多折叠的续行数(栈帧上限,防止一条超长异常把文档撑爆) */
    private static final int MAX_FOLD_LINES = 80;

    private final Path indexDir;
    private final List<Path> logDirs;
    private final String logPattern;

    private Directory directory;
    private IndexWriter writer;
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private final Map<String, FileSig> sigs = new TreeMap<>();

    /** Spring 装配用:从配置读取。 */
    @org.springframework.beans.factory.annotation.Autowired
    public LogIndexService(@Value("${logsearch.index-dir}") String indexDir,
                           @Value("${logsearch.log-dirs}") String logDirs,
                           @Value("${logsearch.log-pattern}") String logPattern) {
        this(Paths.get(indexDir), splitPaths(logDirs), logPattern);
    }

    /** 测试/手动装配用。 */
    public LogIndexService(Path indexDir, List<Path> logDirs, String logPattern) {
        this.indexDir = indexDir;
        this.logDirs = logDirs;
        this.logPattern = logPattern;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(indexDir);
        directory = FSDirectory.open(indexDir);
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND));
        refresh();
    }

    @PreDestroy
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
        if (directory != null) {
            directory.close();
        }
    }

    // ------------------------------------------------------------ ingest
    /** 刷新索引:日志文件有变化则重建(含首次加载)。 */
    public synchronized void refresh() throws IOException {
        List<Path> files = scanLogFiles();
        boolean changed = files.size() != sigs.size();
        if (!changed) {
            for (Path p : files) {
                FileSig sig = FileSig.of(p);
                FileSig old = sigs.get(p.toAbsolutePath().toString());
                if (!sig.equals(old)) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return;
        }
        writer.deleteAll();
        writer.commit();
        sigs.clear();
        int indexed = 0, skipped = 0;
        for (Path p : files) {
            int[] c = ingestFile(p);
            indexed += c[0];
            skipped += c[1];
            sigs.put(p.toAbsolutePath().toString(), FileSig.of(p));
        }
        writer.commit();
        log.info("[logsearch] 索引刷新完成: 文档 {} 条, 跳过 {} 行(无时间戳/不可解析), 文件 {} 个",
                indexed, skipped, files.size());
    }

    private List<Path> scanLogFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : logDirs) {
            if (!Files.isDirectory(dir)) {
                log.warn("[logsearch] 日志目录不存在,跳过: {}", dir);
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches(logPattern))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    /** 返回 [索引行数, 跳过行数]。 */
    private int[] ingestFile(Path path) throws IOException {
        LogEntry pending = null;   // 当前折叠中的条目
        int folded = 0;            // 已折叠的续行数
        int indexed = 0, skipped = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            LogEntry e = LogParser.parse(line);
            if (e != null) {
                if (pending != null) {          // 前一条到此为止,落索引
                    writer.addDocument(toDoc(pending));
                    indexed++;
                }
                pending = e;
                folded = 0;
                continue;
            }
            // 不可解析的行:若跟在 WARN/ERROR 之后且符合异常栈形态(Caused by / 栈帧 /
            // 异常类名),折叠进该条目 —— 让"根因 cause"进入索引,可被 keyword/regex 检索。
            // 其它不可解析行(PERF 指标、Spring banner、空行、connect 无时间戳格式)保持跳过,
            // 不对 INFO 后的常见行折叠,避免噪音污染。
            if (pending != null && folded < MAX_FOLD_LINES
                    && (pending.getLevel().equals("ERROR") || pending.getLevel().equals("WARN"))
                    && isStackLine(line)) {
                pending.setMsg(pending.getMsg() + "\n" + line);
                pending.setRaw(pending.getRaw() + "\n" + line);
                folded++;
                continue;
            }
            skipped++;
        }
        if (pending != null) {                  // 收尾:落最后一条
            writer.addDocument(toDoc(pending));
            indexed++;
        }
        return new int[]{indexed, skipped};
    }

    /** 该行是否像一条异常栈续行(末尾截断的堆栈 / Caused by / Suppressed / 异常类声明)。 */
    private static boolean isStackLine(String line) {
        String t = line.stripLeading();
        return t.startsWith("at ")                       // org... 栈帧
                || t.startsWith("Caused by:")            // 根因链
                || t.startsWith("Suppressed:")
                || t.matches("\\.\\.\\. \\d+.*")         // "... N more" / "... N common frames omitted"
                || t.matches("\\p{L}[\\p{L}\\p{N}_$]*(\\.[\\p{L}\\p{N}_$]+)+(Exception|Error|Throwable)([:\\s].*)?");  // 异常类声明行
    }

    private Document toDoc(LogEntry e) {
        Document d = new Document();
        d.add(new LongPoint("ts", e.getTsMillis()));
        d.add(new StoredField("ts", e.getTsMillis()));
        d.add(new org.apache.lucene.document.NumericDocValuesField("ts_sort", e.getTsMillis()));
        d.add(new StringField("level", e.getLevel(), Field.Store.YES));
        d.add(new StringField("logger", nullSafe(e.getLogger()), Field.Store.YES));
        d.add(new StringField("thread", nullSafe(e.getThread()), Field.Store.YES));
        d.add(new StringField("trace_id", nullSafe(e.getTraceId()), Field.Store.YES));
        d.add(new StringField("conv", nullSafe(e.getConv()), Field.Store.YES));
        d.add(new TextField("msg", nullSafe(e.getMsg()), Field.Store.YES));
        d.add(new StringField("raw", e.getRaw(), Field.Store.YES));
        return d;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------ search
    /** 执行查询,返回 {total, levels:{INFO:..}, hits:[{ts,level,logger,thread,trace_id,conv,msg,raw}]}。 */
    public Map<String, Object> search(SearchQuery q) throws IOException {
        refresh();

        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        long from = toMillis(q.getTimeFrom(), 0L);
        long to = toMillis(q.getTimeTo(), Long.MAX_VALUE);
        bq.add(LongPoint.newRangeQuery("ts", from, to), BooleanClause.Occur.MUST);
        addTerm(bq, "level", q.getLevel());
        addTerm(bq, "trace_id", q.getTraceId());
        addTerm(bq, "conv", q.getConv());
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            try {
                Query p = new QueryParser("msg", analyzer).parse(q.getKeyword());
                bq.add(p, BooleanClause.Occur.MUST);
            } catch (Exception e) {
                bq.add(new TermQuery(new org.apache.lucene.index.Term("msg", q.getKeyword())),
                        BooleanClause.Occur.MUST);
            }
        }
        if (q.getRegex() != null && !q.getRegex().isBlank()) {
            bq.add(new RegexpQuery(new org.apache.lucene.index.Term("raw", q.getRegex())),
                    BooleanClause.Occur.MUST);
        }
        Query query = bq.build();
        int limit = Math.min(q.getLimit() == null ? 50 : q.getLimit(), 2000);

        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Sort sort = new Sort(new SortField("ts_sort", SortField.Type.LONG, true));
            TopDocs top = searcher.search(query, limit, sort);
            StoredFields stored = searcher.storedFields();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", top.totalHits.value);
            result.put("took_millis", 0);
            List<Map<String, Object>> hits = new ArrayList<>();
            for (var score : top.scoreDocs) {
                Document d = stored.document(score.doc);
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("ts", d.get("ts"));
                h.put("level", d.get("level"));
                h.put("logger", d.get("logger"));
                h.put("thread", d.get("thread"));
                h.put("trace_id", d.get("trace_id"));
                h.put("conv", d.get("conv"));
                h.put("msg", d.get("msg"));
                h.put("raw", d.get("raw"));
                hits.add(h);
            }
            result.put("hits", hits);

            // 级别统计:在相同条件上各跑一次 count(小索引够用)
            Map<String, Long> levels = new TreeMap<>();
            for (String lv : LEVELS) {
                levels.put(lv, (long) searcher.count(applyLevel(query, lv)));
            }
            result.put("levels", levels);
            return result;
        }
    }

    private void addTerm(BooleanQuery.Builder bq, String field, String value) {
        if (value != null && !value.isBlank()) {
            bq.add(new TermQuery(new org.apache.lucene.index.Term(field, value)),
                    BooleanClause.Occur.MUST);
        }
    }

    private Query applyLevel(Query base, String level) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(base, BooleanClause.Occur.MUST);
        b.add(new TermQuery(new org.apache.lucene.index.Term("level", level)),
                BooleanClause.Occur.MUST);
        return b.build();
    }

    private static long toMillis(Object v, long dft) {
        if (v == null) {
            return dft;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = v.toString().trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            // 否则当 ISO-8601(如 2026-08-13T19:59:27+08:00)
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception e) {
            return dft;
        }
    }

    private static List<Path> splitPaths(String commaList) {
        List<Path> out = new ArrayList<>();
        for (String s : commaList.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(Paths.get(t));
            }
        }
        return out;
    }

    /** 文件指纹(mtime,size),用于检测日志新增/变化。 */
    record FileSig(long mtime, long size) {
        static FileSig of(Path p) {
            try {
                return new FileSig(Files.getLastModifiedTime(p).toMillis(), Files.size(p));
            } catch (IOException e) {
                return new FileSig(0, 0);
            }
        }
    }
}
