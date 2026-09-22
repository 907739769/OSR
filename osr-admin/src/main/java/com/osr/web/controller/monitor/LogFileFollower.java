package com.osr.web.controller.monitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 实时日志的文件跟随器：从<b>指定偏移</b>开始持续读新写入的行，按批交给 {@link Sink}。
 *
 * <p>替换掉的是 commons-io 的 {@code Tailer}。它只能「从头」或「从尾」开始，
 * 而实时日志页是「先推最后 N 行历史、再跟随新行」两步：读完历史到 Tailer 就位之间写进文件的行，
 * 历史里没有、Tailer 又从更靠后的末尾起步，<b>两边都不推</b>。那个窗口恰好在日志最密的时候最大——
 * 也就是出问题、有人打开这个页面的时候。现在由 {@link #readTail} 返回历史读到的精确边界，
 * 跟随器从这个边界接着读，一个字节都不重不漏。
 *
 * <p>另外三件 Tailer 做不好的事：
 * <ul>
 *   <li><b>按批交付</b>：一轮轮询读到的所有行一次交出去，WebSocket 上是一帧而不是 N 帧。</li>
 *   <li><b>滚动检测</b>：logback 按大小/日期滚动是「改名旧文件 + 新建同名文件」。已打开的句柄
 *       仍指向改名后的旧文件（Linux 语义），所以判据是路径上的 fileKey（inode）变了或长度缩了；
 *       换文件前先把旧句柄<b>读干</b>，滚动前最后那几行不丢。</li>
 *   <li><b>半行</b>：读到的末尾不以换行结束时先攒着，等换行到了再作为完整一行交出去。</li>
 * </ul>
 *
 * <p>一个连接一个实例，跑在自己的虚拟线程里；{@link #stop()} 后最多一个轮询间隔内退出。
 */
final class LogFileFollower implements Runnable {

    /** 行的去向。抛出 IOException 视为对端已不可写，跟随器随即退出 */
    interface Sink {
        void lines(List<String> lines) throws IOException;

        /** 日志文件发生了滚动（换成了一个新文件） */
        void rotated() throws IOException;

        /** 读文件本身失败（不含 Sink 自己抛的异常），跟随器随即退出 */
        void failed(IOException e);
    }

    /** 单批最多交付的行数，防止一次 DEBUG 洪水攒出一帧几 MB 的消息 */
    static final int MAX_BATCH = 500;

    private static final int READ_BUF = 64 * 1024;

    private final Path path;
    private final long startPos;
    private final long pollMillis;
    private final Sink sink;
    private volatile boolean running = true;

    LogFileFollower(Path path, long startPos, long pollMillis, Sink sink) {
        this.path = path;
        this.startPos = startPos;
        this.pollMillis = pollMillis;
        this.sink = sink;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        LineSplitter splitter = new LineSplitter();
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(path.toFile(), "r");
            raf.seek(Math.min(startPos, raf.length()));
            Object key = fileKey(path);

            while (running) {
                boolean got = drain(raf, splitter);

                Object nowKey;
                long nowLen;
                try {
                    nowKey = fileKey(path);
                    nowLen = Files.size(path);
                } catch (NoSuchFileException e) {
                    // 滚动的瞬间：旧文件已改名、新文件要等下一条日志写入才会建出来
                    sleep();
                    continue;
                }

                boolean replaced = key != null && nowKey != null && !key.equals(nowKey);
                if (replaced || nowLen < raf.getFilePointer()) {
                    // 旧句柄里改名前最后写进去的内容先读干，再换到新文件
                    drain(raf, splitter);
                    List<String> tail = splitter.flushPartial();
                    if (!tail.isEmpty()) {
                        sink.lines(tail);
                    }
                    raf.close();
                    raf = new RandomAccessFile(path.toFile(), "r");
                    key = nowKey;
                    sink.rotated();
                    continue;
                }

                if (!got) {
                    sleep();
                }
            }
        } catch (SinkClosedException e) {
            // 对端已不可写（连接关了），正常退出
        } catch (IOException e) {
            if (running) {
                sink.failed(e);
            }
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                    // 只读句柄，关闭失败无后果
                }
            }
        }
    }

    /** 把句柄读到 EOF，完整的行按批交给 sink。返回本次是否读到了任何字节 */
    private boolean drain(RandomAccessFile raf, LineSplitter splitter) throws IOException {
        byte[] buf = new byte[READ_BUF];
        boolean got = false;
        List<String> batch = new ArrayList<>();
        int n;
        while (running && (n = raf.read(buf)) > 0) {
            got = true;
            splitter.feed(buf, n, batch);
            if (batch.size() >= MAX_BATCH) {
                deliver(batch);
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            deliver(batch);
        }
        return got;
    }

    private void deliver(List<String> batch) throws SinkClosedException {
        try {
            sink.lines(batch);
        } catch (IOException e) {
            throw new SinkClosedException(e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private static Object fileKey(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class).fileKey();
    }

    /** Sink 抛出的 IOException，与读文件失败区分开 */
    private static final class SinkClosedException extends IOException {
        SinkClosedException(IOException cause) {
            super(cause);
        }
    }

    /**
     * 字节流切行。按字节找 {@code \n} 再整行解码，而不是边读边解码：
     * 一次 read 的边界可能落在一个中文字符的三个字节中间，分块解码会产出乱码。
     */
    static final class LineSplitter {
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

        void feed(byte[] buf, int len, List<String> out) {
            int start = 0;
            for (int i = 0; i < len; i++) {
                if (buf[i] == '\n') {
                    partial.write(buf, start, i - start);
                    out.add(decode(partial.toByteArray()));
                    partial.reset();
                    start = i + 1;
                }
            }
            if (start < len) {
                partial.write(buf, start, len - start);
            }
        }

        List<String> flushPartial() {
            if (partial.size() == 0) {
                return List.of();
            }
            String s = decode(partial.toByteArray());
            partial.reset();
            return List.of(s);
        }

        private static String decode(byte[] bytes) {
            int len = bytes.length;
            if (len > 0 && bytes[len - 1] == '\r') {
                len--;
            }
            return new String(bytes, 0, len, StandardCharsets.UTF_8);
        }
    }

    /**
     * 读文件末尾的若干完整行，同时给出跟随器应当接着读的偏移。
     *
     * @param end      读取的上界（调用方先取一次文件长度传进来，之后写入的内容归跟随器）
     * @param maxLines 最多返回的行数
     * @param maxBytes 最多往回扫的字节数，防止一个几十 MB 的无换行文件把内存吃掉
     * @return 行按时间正序；{@code resumePos} 是最后一个换行之后的位置——上界若切在一行中间，
     *         那半行不进历史，整行留给跟随器，否则会被劈成两条显示
     */
    static Tail readTail(Path file, long end, int maxLines, long maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            end = Math.min(end, raf.length());

            // 先把上界收到最后一个换行之后
            long resume = end;
            byte[] one = new byte[1];
            while (resume > 0) {
                raf.seek(resume - 1);
                raf.readFully(one);
                if (one[0] == '\n') {
                    break;
                }
                resume--;
                if (end - resume > maxBytes) {
                    // 末尾一整段都没有换行：放弃历史，从原上界开始跟随
                    return new Tail(List.of(), end);
                }
            }
            if (resume == 0) {
                return new Tail(List.of(), 0);
            }

            // 从 resume 往回按块读，直到凑够 maxLines 个换行（多一个用来定位首行起点）或触底
            long pos = resume;
            int newlines = 0;
            List<byte[]> chunks = new ArrayList<>();
            while (pos > 0 && newlines <= maxLines && resume - pos < maxBytes) {
                int size = (int) Math.min(READ_BUF, pos);
                pos -= size;
                byte[] chunk = new byte[size];
                raf.seek(pos);
                raf.readFully(chunk);
                chunks.add(0, chunk);
                for (byte b : chunk) {
                    if (b == '\n') {
                        newlines++;
                    }
                }
            }

            ByteArrayOutputStream all = new ByteArrayOutputStream((int) (resume - pos));
            for (byte[] c : chunks) {
                all.write(c, 0, c.length);
            }
            byte[] bytes = all.toByteArray();

            List<String> lines = new ArrayList<>();
            LineSplitter splitter = new LineSplitter();
            splitter.feed(bytes, bytes.length, lines);
            // 没读到文件开头时，第一段是被块边界切断的半行（还可能切在多字节字符中间），丢掉
            if (pos > 0 && !lines.isEmpty()) {
                lines.remove(0);
            }
            if (lines.size() > maxLines) {
                lines = new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
            }
            return new Tail(lines, resume);
        }
    }

    record Tail(List<String> lines, long resumePos) {
    }
}
