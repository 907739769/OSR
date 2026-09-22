package com.osr.web.controller.monitor;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 在日志文件（含已滚动出去的分片）里检索。
 *
 * <p>实时日志页只有最近 500 行历史 + 连上之后新来的行，而排查时最常见的起点恰恰是
 * 「通知里给了一个 traceId / 一个时间点，那是半小时前的事」——此前只能 {@code docker cp} 出来 grep。
 *
 * <p>几条设计取舍：
 * <ul>
 *   <li><b>以「一条日志」为单位判定，不是以「一行」</b>：首行加它后面的续行（异常堆栈）是一条。
 *       关键字命中其中任意一行，整条都返回——搜异常类名要能带出首行的时间与 traceId，
 *       搜 traceId 要能带出整段堆栈；按行判定的话两头都只剩孤零零的一行。</li>
 *   <li><b>返回最新的 N 行</b>：文件从新到旧扫，凑够就停，结果按时间正序返回，
 *       与实时视图的阅读方向一致。</li>
 *   <li><b>扫描有上限</b>：字节数与耗时两道闸，任一触发就带着已有结果返回并标明截断原因——
 *       全量日志上限 2GB，一次检索不应该把它全读一遍。时间范围会先按文件名里的日期跳过无关分片。</li>
 *   <li><b>用户输入的正则带超时</b>：灾难性回溯的正则（如 {@code (a+)+b}）会让单次 match 跑上几分钟，
 *       逐行检查耗时拦不住它，所以匹配对象包一层到点就抛异常的 CharSequence。</li>
 * </ul>
 */
@Service
public class LogSearchService {

    private static final Logger log = LoggerFactory.getLogger(LogSearchService.class);

    static final int DEFAULT_LIMIT = 1000;
    static final int MAX_LIMIT = 5000;
    private static final long MAX_SCAN_BYTES = 512L * 1024 * 1024;
    private static final long TIME_BUDGET_MILLIS = 15_000;

    /** 日志源 → 文件名前缀，与 {@link LogWebSocket} 的三档一致 */
    static String prefixOf(String type) {
        if ("error".equalsIgnoreCase(type)) {
            return "sys-error";
        }
        if ("access".equalsIgnoreCase(type)) {
            return "sys-access";
        }
        return "sys-all";
    }

    private final Path baseDir;

    @Autowired
    public LogSearchService() {
        this(Path.of("/data/logs"));
    }

    LogSearchService(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * @param from 起始时间（含），{@code yyyy-MM-dd HH:mm[:ss[.SSS]]}，可空
     * @param to   结束时间（含），同上，可空；只精确到分钟时包含这一整分钟
     */
    public record Query(String type, String keyword, boolean regex, LogWebSocket.LevelFilter levels,
                        String from, String to, int limit) {
    }

    public record SearchResult(List<JSONObject> lines, boolean truncated, String truncatedReason,
                               int scannedFiles, long scannedBytes, long elapsedMs) {
    }

    /** 关键字或正则非法时抛出，消息可直接给用户看 */
    public static class InvalidQueryException extends RuntimeException {
        InvalidQueryException(String message) {
            super(message);
        }
    }

    public SearchResult search(Query q) throws IOException {
        long start = System.currentTimeMillis();
        long deadline = start + TIME_BUDGET_MILLIS;
        int limit = q.limit() <= 0 ? DEFAULT_LIMIT : Math.min(q.limit(), MAX_LIMIT);
        String from = normalizeTime(q.from(), false);
        String to = normalizeTime(q.to(), true);
        Predicate<String> keyword = compileKeyword(q.keyword(), q.regex(), deadline);

        List<LogFile> files = listFiles(prefixOf(q.type()));
        // 从新到旧扫，每个文件的结果先入栈，最后倒过来拼成时间正序
        Deque<List<JSONObject>> perFile = new ArrayDeque<>();
        int total = 0;
        int scannedFiles = 0;
        long scannedBytes = 0;
        String truncatedReason = null;

        for (LogFile f : files) {
            if (f.date != null && ((from != null && f.date.compareTo(from.substring(0, 10)) < 0)
                    || (to != null && f.date.compareTo(to.substring(0, 10)) > 0))) {
                continue;
            }
            if (scannedBytes >= MAX_SCAN_BYTES) {
                truncatedReason = "已达单次扫描上限（" + (MAX_SCAN_BYTES >> 20) + "MB），更早的日志未检索";
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                truncatedReason = "检索超过 " + TIME_BUDGET_MILLIS / 1000 + " 秒，更早的日志未检索";
                break;
            }
            ScanOutcome o;
            try {
                o = scanFile(f.path, keyword, q.levels(), from, to, limit, deadline);
            } catch (DeadlineExceeded e) {
                truncatedReason = "检索超过 " + TIME_BUDGET_MILLIS / 1000 + " 秒，更早的日志未检索";
                break;
            }
            scannedFiles++;
            scannedBytes += o.bytes;
            if (!o.lines.isEmpty()) {
                perFile.push(o.lines);
                total += o.lines.size();
            }
            if (o.timedOut) {
                truncatedReason = "检索超过 " + TIME_BUDGET_MILLIS / 1000 + " 秒，本文件及更早的日志只检索了一部分";
                break;
            }
            if (total >= limit) {
                truncatedReason = "命中超过 " + limit + " 行，只返回最新的 " + limit + " 行";
                break;
            }
        }

        List<JSONObject> lines = new ArrayList<>(Math.min(total, limit));
        for (List<JSONObject> part : perFile) {
            lines.addAll(part);
        }
        if (lines.size() > limit) {
            lines = new ArrayList<>(lines.subList(lines.size() - limit, lines.size()));
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("日志检索完成：type={}, keyword={}, regex={}, 时间范围=[{} ~ {}]，命中 {} 行，扫描 {} 个文件 / {} KB，用时 {}ms{}",
                q.type(), q.keyword(), q.regex(), from, to, lines.size(), scannedFiles, scannedBytes >> 10, elapsed,
                truncatedReason == null ? "" : "（" + truncatedReason + "）");
        return new SearchResult(lines, truncatedReason != null, truncatedReason, scannedFiles, scannedBytes, elapsed);
    }

    private record ScanOutcome(List<JSONObject> lines, long bytes, boolean timedOut) {
    }

    /**
     * 正序扫一个文件，按条判定，保留最后 limit 行命中。
     * 每个文件从头开始解析：跨文件的续行（极少见，只在滚动恰好切在一段堆栈中间时出现）按 INFO 起算。
     */
    private ScanOutcome scanFile(Path file, Predicate<String> keyword, LogWebSocket.LevelFilter levels,
                                 String from, String to, int limit, long deadline) throws IOException {
        Deque<JSONObject> kept = new ArrayDeque<>();
        long bytes = Files.size(file);
        Entry entry = new Entry();
        int n = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if ((++n & 1023) == 0 && System.currentTimeMillis() > deadline) {
                    return new ScanOutcome(new ArrayList<>(kept), bytes, true);
                }
                Matcher m = HEAD.matcher(line);
                if (m.lookingAt()) {
                    entry.flushInto(kept, keyword, levels, from, to, limit);
                    entry.start(line, m.group(1), m.group(2).trim());
                } else {
                    entry.add(line);
                }
            }
        }
        entry.flushInto(kept, keyword, levels, from, to, limit);
        return new ScanOutcome(new ArrayList<>(kept), bytes, false);
    }

    /** 只取判定首行所需的时间与级别，完整解析留给命中之后的 {@link LogWebSocket.LineCodec} */
    private static final Pattern HEAD = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\]\\[[^\\]]*\\]\\[([A-Z ]{1,5})\\]");

    /** 当前正在累积的一条日志：首行 + 续行 */
    private static final class Entry {
        private final List<String> lines = new ArrayList<>();
        private String ts;
        private String level = "INFO";

        void start(String head, String ts, String level) {
            lines.clear();
            lines.add(head);
            this.ts = ts;
            this.level = level;
        }

        void add(String line) {
            lines.add(line);
        }

        void flushInto(Deque<JSONObject> kept, Predicate<String> keyword, LogWebSocket.LevelFilter levels,
                       String from, String to, int limit) {
            if (lines.isEmpty()) {
                return;
            }
            try {
                if (!accept(keyword, levels, from, to)) {
                    return;
                }
                LogWebSocket.LineCodec codec = new LogWebSocket.LineCodec();
                for (String l : lines) {
                    kept.addLast(codec.parse(l));
                    if (kept.size() > limit) {
                        kept.removeFirst();
                    }
                }
            } finally {
                lines.clear();
            }
        }

        private boolean accept(Predicate<String> keyword, LogWebSocket.LevelFilter levels, String from, String to) {
            if (!levels.accepts(level)) {
                return false;
            }
            // 文件开头没有首行的续行（ts 为空）不参与时间过滤
            if (ts != null && ((from != null && ts.compareTo(from) < 0) || (to != null && ts.compareTo(to) > 0))) {
                return false;
            }
            if (keyword == null) {
                return true;
            }
            for (String l : lines) {
                if (keyword.test(l)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 与前端一致：普通模式按字面、不区分大小写；正则模式不区分大小写 */
    static Predicate<String> compileKeyword(String keyword, boolean regex, long deadline) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String kw = keyword.trim();
        if (!regex) {
            String lower = kw.toLowerCase();
            return s -> s.toLowerCase().contains(lower);
        }
        Pattern p;
        try {
            p = Pattern.compile(kw, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw new InvalidQueryException("正则表达式无效：" + e.getDescription());
        }
        return s -> p.matcher(new DeadlineCharSequence(s, deadline)).find();
    }

    /**
     * 把用户输入的时间规整成与日志时间戳可直接按字典序比较的形态 {@code yyyy-MM-dd HH:mm:ss.SSS}。
     * 前端 datetime-local 给的是 {@code 2026-09-22T10:30}，结束时间只到分钟时补成这一分钟的最后一毫秒。
     */
    static String normalizeTime(String t, boolean end) {
        if (t == null || t.isBlank()) {
            return null;
        }
        String s = t.trim().replace('T', ' ');
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,3})?)?)?")) {
            throw new InvalidQueryException("时间格式应为 yyyy-MM-dd HH:mm：" + t);
        }
        String full = end ? "0000-00-00 23:59:59.999" : "0000-00-00 00:00:00.000";
        return s + full.substring(s.length());
    }

    record LogFile(Path path, String date, int index) {
    }

    private static final Pattern ROLLED = Pattern.compile("\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.log$");

    /** 当前文件 + 滚动分片（{@code prefix.yyyy-MM-dd.i.log}），从新到旧 */
    List<LogFile> listFiles(String prefix) throws IOException {
        List<LogFile> rolled = new ArrayList<>();
        LogFile current = null;
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(baseDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                if (name.equals(prefix + ".log")) {
                    current = new LogFile(p, null, Integer.MAX_VALUE);
                } else if (name.startsWith(prefix + ".")) {
                    Matcher m = ROLLED.matcher(name);
                    // 前缀之后紧跟日期，防止 sys-all 误吃到别的同前缀文件
                    if (m.find() && m.start() == prefix.length()) {
                        rolled.add(new LogFile(p, m.group(1), Integer.parseInt(m.group(2))));
                    }
                }
            }
        }
        rolled.sort(Comparator.comparing(LogFile::date).thenComparingInt(LogFile::index).reversed());
        List<LogFile> out = new ArrayList<>(rolled.size() + 1);
        if (current != null) {
            out.add(current);
        }
        out.addAll(rolled);
        return out;
    }

    /** 到点就抛异常的 CharSequence，给用户正则的单次 match 设上限 */
    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadline;
        private int calls;

        DeadlineCharSequence(CharSequence inner, long deadline) {
            this.inner = inner;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++calls & 0xFFFF) == 0 && System.currentTimeMillis() > deadline) {
                throw new DeadlineExceeded();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }

    private static final class DeadlineExceeded extends RuntimeException {
        DeadlineExceeded() {
            super(null, null, false, false);
        }
    }
}
