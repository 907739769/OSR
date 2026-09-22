package com.osr.web.controller.monitor;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSearchServiceTest {

    @TempDir
    Path dir;

    private void write(String name, String... lines) throws IOException {
        Files.writeString(dir.resolve(name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static String head(String ts, String trace, String level, String logger, String msg) {
        return "[" + ts + "][" + trace + "][" + String.format("%-5s", level) + "][" + logger + "] " + msg;
    }

    private LogSearchService.SearchResult search(String keyword, boolean regex, String levels, String from, String to, int limit)
            throws IOException {
        return new LogSearchService(dir).search(new LogSearchService.Query(
                "all", keyword, regex, LogWebSocket.LevelFilter.parse(levels), from, to, limit));
    }

    private static List<String> msgs(LogSearchService.SearchResult r) {
        return r.lines().stream().map(o -> o.getString("msg")).toList();
    }

    @Test
    @DisplayName("跨滚动分片检索，结果按时间正序；不会误吃同前缀的其它文件")
    void searchesRolledFilesInOrder() throws IOException {
        write("sys-all.2026-09-20.0.log", head("2026-09-20 10:00:00.000", "t1", "INFO", "A", "hit 1"));
        write("sys-all.2026-09-21.0.log", head("2026-09-21 10:00:00.000", "t2", "INFO", "A", "hit 2"));
        write("sys-all.2026-09-21.1.log", head("2026-09-21 20:00:00.000", "t3", "INFO", "A", "hit 3"));
        write("sys-all.log", head("2026-09-22 10:00:00.000", "t4", "INFO", "A", "hit 4"),
                head("2026-09-22 10:00:01.000", "t5", "INFO", "A", "miss"));
        write("sys-access.log", head("2026-09-22 10:00:00.000", "t9", "INFO", "access", "hit access"));

        LogSearchService.SearchResult r = search("hit", false, null, null, null, 0);

        assertEquals(List.of("hit 1", "hit 2", "hit 3", "hit 4"), msgs(r));
        assertEquals(4, r.scannedFiles());
        assertFalse(r.truncated());
    }

    @Test
    @DisplayName("按条判定：关键字命中堆栈里的类名，整条（含首行）返回；命中 traceId 带出整段堆栈")
    void matchesWholeEntry() throws IOException {
        write("sys-all.log",
                head("2026-09-22 10:00:00.000", "abc", "ERROR", "AsynHelper", "复制任务失败"),
                "java.lang.IllegalStateException: boom",
                "\tat com.osr.Foo.bar(Foo.java:1)",
                head("2026-09-22 10:00:01.000", "def", "INFO", "Other", "正常"));

        LogSearchService.SearchResult byClass = search("IllegalStateException", false, null, null, null, 0);
        assertEquals(3, byClass.lines().size());
        assertEquals("abc", byClass.lines().get(0).getString("trace"));
        // 续行继承首行的 ERROR
        assertEquals("ERROR", byClass.lines().get(2).getString("level"));
        assertEquals(Boolean.TRUE, byClass.lines().get(2).getBoolean("cont"));

        assertEquals(3, search("abc", false, null, null, null, 0).lines().size());
    }

    @Test
    @DisplayName("级别与时间范围过滤；结束时间只到分钟时包含这一整分钟；按文件名日期跳过无关分片")
    void levelAndTimeFilter() throws IOException {
        write("sys-all.2026-09-20.0.log", head("2026-09-20 10:00:00.000", "t", "ERROR", "A", "old error"));
        write("sys-all.log",
                head("2026-09-22 10:29:59.999", "t", "ERROR", "A", "before"),
                head("2026-09-22 10:30:00.000", "t", "DEBUG", "A", "debug in range"),
                head("2026-09-22 10:30:30.000", "t", "ERROR", "A", "error in range"),
                head("2026-09-22 10:31:00.000", "t", "ERROR", "A", "after"));

        LogSearchService.SearchResult r = search(null, false, "WARN,ERROR", "2026-09-22T10:30", "2026-09-22T10:30", 0);

        assertEquals(List.of("error in range"), msgs(r));
        // 09-20 那个分片按文件名日期直接跳过
        assertEquals(1, r.scannedFiles());
    }

    @Test
    @DisplayName("命中超过上限时只返回最新的 N 行并标明截断")
    void limitKeepsNewest() throws IOException {
        write("sys-all.2026-09-21.0.log", head("2026-09-21 10:00:00.000", "t", "INFO", "A", "x old"));
        write("sys-all.log",
                head("2026-09-22 10:00:00.000", "t", "INFO", "A", "x 1"),
                head("2026-09-22 10:00:01.000", "t", "INFO", "A", "x 2"),
                head("2026-09-22 10:00:02.000", "t", "INFO", "A", "x 3"));

        LogSearchService.SearchResult r = search("x", false, null, null, null, 2);

        assertEquals(List.of("x 2", "x 3"), msgs(r));
        assertTrue(r.truncated());
        // 最新的文件已经凑够，更早的分片不必再读
        assertEquals(1, r.scannedFiles());
    }

    @Test
    @DisplayName("普通模式按字面匹配、不区分大小写；正则模式可用；非法正则给出可读的错误")
    void keywordModes() throws IOException {
        write("sys-all.log",
                head("2026-09-22 10:00:00.000", "t", "INFO", "A", "a.b TIMEOUT"),
                head("2026-09-22 10:00:01.000", "t", "INFO", "A", "axb"));

        assertEquals(List.of("a.b TIMEOUT"), msgs(search("a.b", false, null, null, null, 0)));
        assertEquals(List.of("a.b TIMEOUT"), msgs(search("timeout", false, null, null, null, 0)));
        assertEquals(List.of("a.b TIMEOUT", "axb"), msgs(search("a.b", true, null, null, null, 0)));
        LogSearchService.InvalidQueryException e = assertThrows(LogSearchService.InvalidQueryException.class,
                () -> search("(", true, null, null, null, 0));
        assertTrue(e.getMessage().startsWith("正则表达式无效"));
    }

    @Test
    @DisplayName("灾难性回溯的正则受耗时上限约束，不会把请求线程卡死")
    void catastrophicRegexIsBounded() {
        long deadline = System.currentTimeMillis() + 200;
        var p = LogSearchService.compileKeyword("(a+)+b", true, deadline);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            try {
                p.test("a".repeat(40));
            } catch (RuntimeException expected) {
                // 到点抛出即可
            }
        });
    }

    @Test
    @DisplayName("时间规整")
    void normalizeTime() {
        assertEquals("2026-09-22 10:30:00.000", LogSearchService.normalizeTime("2026-09-22T10:30", false));
        assertEquals("2026-09-22 10:30:59.999", LogSearchService.normalizeTime("2026-09-22T10:30", true));
        assertEquals("2026-09-22 23:59:59.999", LogSearchService.normalizeTime("2026-09-22", true));
        assertNull(LogSearchService.normalizeTime(" ", false));
        assertThrows(LogSearchService.InvalidQueryException.class, () -> LogSearchService.normalizeTime("昨天", false));
    }

    @Test
    @DisplayName("日志目录不存在时返回空结果")
    void missingDir() throws IOException {
        LogSearchService.SearchResult r = new LogSearchService(dir.resolve("nope")).search(
                new LogSearchService.Query("all", "x", false, LogWebSocket.LevelFilter.parse(null), null, null, 0));
        assertEquals(List.<JSONObject>of(), r.lines());
    }
}
