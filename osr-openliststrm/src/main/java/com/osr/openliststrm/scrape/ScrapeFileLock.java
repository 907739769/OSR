package com.osr.openliststrm.scrape;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按目标文件路径加锁，避免批量刮削时多个任务并发写同一 NFO/图片文件（如 tvshow.nfo、season-poster.jpg）导致内容交错。
 * 仅包内可见，供 {@link NfoGenerator} 与 {@link MediaImageDownloader} 复用。
 */
final class ScrapeFileLock {

    /** 引用计数锁，避免"释放后又被新线程复用同一把已失效锁"的竞态（同一 key 被两个线程各自持有不同锁对象保护） */
    private static final ConcurrentMap<String, LockEntry> LOCKS = new ConcurrentHashMap<>();

    private ScrapeFileLock() {
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger refCount = new AtomicInteger(0);
    }

    static void withLock(Path target, IoAction action) throws IOException {
        String key = target.toAbsolutePath().normalize().toString();
        LockEntry entry = LOCKS.compute(key, (k, v) -> {
            if (v == null) v = new LockEntry();
            v.refCount.incrementAndGet();
            return v;
        });
        entry.lock.lock();
        try {
            action.run();
        } finally {
            entry.lock.unlock();
            LOCKS.computeIfPresent(key, (k, v) -> v.refCount.decrementAndGet() <= 0 ? null : v);
        }
    }
}
