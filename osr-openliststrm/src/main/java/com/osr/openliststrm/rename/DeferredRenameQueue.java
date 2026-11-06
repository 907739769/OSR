package com.osr.openliststrm.rename;

import com.osr.common.utils.Threads;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/** 按源路径合并有限次数的延后处理。停止监控时取消，不占用线程等待元数据。 */
public final class DeferredRenameQueue implements AutoCloseable {
    private final TaskScheduler scheduler;
    private final int maxAttempts;
    private final Map<Path, Entry> entries = new HashMap<>();
    private boolean closed;

    private static final class Entry {
        int attempts;
        boolean scheduled;
        Runnable retry;
        Runnable exhausted;
        ScheduledFuture<?> future;
    }

    public DeferredRenameQueue(TaskScheduler scheduler) { this(scheduler, 12); }
    DeferredRenameQueue(TaskScheduler scheduler, int maxAttempts) {
        this.scheduler = scheduler;
        this.maxAttempts = maxAttempts;
    }

    /** 返回是否首次进入等待；重复事件更新参数，但不会为同一路径重复调度。 */
    public boolean defer(Path path, Runnable retry, Runnable exhausted) {
        Runnable terminal = null;
        boolean first;
        synchronized (entries) {
            if (closed) return false;
            first = !entries.containsKey(path);
            Entry entry = entries.computeIfAbsent(path, key -> new Entry());
            entry.retry = retry;
            entry.exhausted = exhausted;
            if (entry.scheduled) return false;
            if (entry.attempts >= maxAttempts) {
                entries.remove(path);
                terminal = exhausted;
            } else {
                long seconds = Math.min(60, 5L << Math.min(entry.attempts, 4));
                entry.attempts++;
                entry.scheduled = true;
                try {
                    entry.future = scheduler.schedule(Threads.wrap(() -> run(path, entry)), Instant.now().plusSeconds(seconds));
                } catch (RuntimeException e) {
                    entries.remove(path);
                    throw e;
                }
            }
        }
        if (terminal != null) terminal.run();
        return first;
    }

    private void run(Path path, Entry entry) {
        Runnable retry;
        synchronized (entries) {
            if (closed || entries.get(path) != entry) return;
            entry.scheduled = false;
            entry.future = null;
            retry = entry.retry;
        }
        try { retry.run(); }
        finally {
            Runnable next = null;
            Runnable terminal = null;
            synchronized (entries) {
                // 文件暂不稳定、或与一个文件事件撞上时，也不会丢掉最后一次重试。
                if (!closed && entries.get(path) == entry && !entry.scheduled) {
                    next = entry.retry;
                    terminal = entry.exhausted;
                }
            }
            if (next != null) defer(path, next, terminal);
        }
    }

    public void complete(Path path) {
        synchronized (entries) {
            Entry entry = entries.remove(path);
            if (entry != null && entry.future != null) entry.future.cancel(false);
        }
    }

    @Override
    public void close() {
        synchronized (entries) {
            closed = true;
            for (Entry entry : entries.values()) if (entry.future != null) entry.future.cancel(false);
            entries.clear();
        }
    }
}
