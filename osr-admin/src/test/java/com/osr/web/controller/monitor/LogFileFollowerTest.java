package com.osr.web.controller.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 钉住实时日志「历史 + 跟随」的衔接。
 *
 * <p>换掉 commons-io Tailer 的全部理由就是它从「当前末尾」起步，读完历史到它就位之间写进来的行两边都不推。
 * 这里最要紧的是 {@link #followerContinuesExactlyWhereHistoryEnded}：历史读完之后、跟随器启动之前写入的行
 * 必须出现，且不与历史重复。
 */
class LogFileFollowerTest {

    @TempDir
    Path dir;

    private static void append(Path f, String s) throws IOException {
        Files.writeString(f, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    @DisplayName("历史：取最后 N 行，按时间正序")
    void readTailReturnsLastLinesInOrder() throws IOException {
        Path f = dir.resolve("a.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("line").append(i).append('\n');
        }
        append(f, sb.toString());

        LogFileFollower.Tail tail = LogFileFollower.readTail(f, Files.size(f), 3, 1 << 20);

        assertEquals(List.of("line8", "line9", "line10"), tail.lines());
        assertEquals(Files.size(f), tail.resumePos());
    }

    @Test
    @DisplayName("历史：上界切在一行中间时，那半行留给跟随器，不劈成两条")
    void readTailStopsAtLastNewline() throws IOException {
        Path f = dir.resolve("b.log");
        append(f, "first\nsecond\nthi");

        LogFileFollower.Tail tail = LogFileFollower.readTail(f, Files.size(f), 10, 1 << 20);

        assertEquals(List.of("first", "second"), tail.lines());
        assertEquals("first\nsecond\n".length(), tail.resumePos());
    }

    @Test
    @DisplayName("历史：跨多个读块、含中文，不产生乱码与半行")
    void readTailAcrossChunksWithMultibyte() throws IOException {
        Path f = dir.resolve("c.log");
        StringBuilder sb = new StringBuilder();
        // 每行约 100 字节，5000 行约 500KB，远超 64KB 的读块
        for (int i = 0; i < 5000; i++) {
            sb.append("第").append(i).append("行 生成 STRM 完成，共 12 个文件 ").append("中".repeat(20)).append('\n');
        }
        append(f, sb.toString());

        LogFileFollower.Tail tail = LogFileFollower.readTail(f, Files.size(f), 1500, 8 << 20);

        assertEquals(1500, tail.lines().size());
        assertTrue(tail.lines().get(0).startsWith("第3500行"), tail.lines().get(0));
        assertTrue(tail.lines().get(1499).startsWith("第4999行"));
        for (String l : tail.lines()) {
            assertTrue(!l.contains("�"), "出现乱码: " + l);
        }
    }

    @Test
    @DisplayName("历史：文件不足 N 行时从头返回，空文件返回空")
    void readTailShortAndEmptyFile() throws IOException {
        Path f = dir.resolve("d.log");
        append(f, "only\n");
        assertEquals(List.of("only"), LogFileFollower.readTail(f, Files.size(f), 500, 1 << 20).lines());

        Path empty = dir.resolve("e.log");
        append(empty, "");
        LogFileFollower.Tail t = LogFileFollower.readTail(empty, 0, 500, 1 << 20);
        assertEquals(List.of(), t.lines());
        assertEquals(0, t.resumePos());
    }

    @Test
    @DisplayName("跟随：从历史的边界接着读，中间写入的行不漏不重")
    void followerContinuesExactlyWhereHistoryEnded() throws Exception {
        Path f = dir.resolve("f.log");
        append(f, "h1\nh2\n");
        LogFileFollower.Tail tail = LogFileFollower.readTail(f, Files.size(f), 500, 1 << 20);

        // 历史已读完、跟随器还没启动——这正是 Tailer(end=true) 会丢掉的那一段
        append(f, "gap1\ngap2\n");

        Collector c = new Collector();
        LogFileFollower follower = new LogFileFollower(f, tail.resumePos(), 20, c);
        Thread t = Thread.ofVirtual().start(follower);
        try {
            append(f, "new1\n");
            waitUntil(() -> c.lines.size() >= 3);
            assertEquals(List.of("h1", "h2"), tail.lines());
            assertEquals(List.of("gap1", "gap2", "new1"), c.lines);
        } finally {
            follower.stop();
            t.join(2000);
        }
    }

    @Test
    @DisplayName("跟随：半行攒着，等换行到了再作为一整行交出")
    void followerJoinsPartialLine() throws Exception {
        Path f = dir.resolve("g.log");
        append(f, "");
        Collector c = new Collector();
        LogFileFollower follower = new LogFileFollower(f, 0, 20, c);
        Thread t = Thread.ofVirtual().start(follower);
        try {
            append(f, "hel");
            Thread.sleep(100);
            assertEquals(List.of(), c.lines);
            append(f, "lo\r\nworld\n");
            waitUntil(() -> c.lines.size() >= 2);
            assertEquals(List.of("hello", "world"), c.lines);
        } finally {
            follower.stop();
            t.join(2000);
        }
    }

    @Test
    @DisplayName("跟随：logback 式滚动（改名 + 新建）后切到新文件，旧文件最后几行不丢")
    void followerHandlesRotation() throws Exception {
        Path f = dir.resolve("h.log");
        append(f, "old1\n");
        Collector c = new Collector();
        LogFileFollower follower = new LogFileFollower(f, Files.size(f), 20, c);
        Thread t = Thread.ofVirtual().start(follower);
        try {
            append(f, "old2\n");
            waitUntil(() -> c.lines.contains("old2"));

            Files.move(f, dir.resolve("h.2026-09-22.0.log"));
            append(f, "new1\n");
            waitUntil(() -> c.lines.contains("new1"));

            assertEquals(List.of("old2", "new1"), c.lines);
            assertEquals(1, c.rotations.get());
        } finally {
            follower.stop();
            t.join(2000);
        }
    }

    @Test
    @DisplayName("跟随：Sink 写失败（连接已断）时退出，不当作读文件失败")
    void followerStopsWhenSinkFails() throws Exception {
        Path f = dir.resolve("i.log");
        append(f, "x\n");
        List<IOException> failures = Collections.synchronizedList(new ArrayList<>());
        LogFileFollower follower = new LogFileFollower(f, 0, 20, new LogFileFollower.Sink() {
            @Override
            public void lines(List<String> lines) throws IOException {
                throw new IOException("连接已关闭");
            }

            @Override
            public void rotated() {
            }

            @Override
            public void failed(IOException e) {
                failures.add(e);
            }
        });
        Thread t = Thread.ofVirtual().start(follower);
        t.join(2000);
        assertTrue(!t.isAlive(), "跟随器应已退出");
        assertEquals(List.of(), failures);
    }

    private static void waitUntil(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("等待超时");
            }
            Thread.sleep(10);
        }
    }

    private static final class Collector implements LogFileFollower.Sink {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final AtomicInteger rotations = new AtomicInteger();

        @Override
        public void lines(List<String> batch) {
            lines.addAll(batch);
        }

        @Override
        public void rotated() {
            rotations.incrementAndGet();
        }

        @Override
        public void failed(IOException e) {
            fail(e);
        }
    }
}
