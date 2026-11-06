package com.osr.openliststrm.rename;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeferredRenameQueueTest {
    @Test
    void 重复事件只调度一次_回调使用最新参数_就绪后停止重试() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        Queue<Runnable> tasks = new ArrayDeque<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(i -> {
            tasks.add(i.getArgument(0)); return mock(ScheduledFuture.class);
        });
        DeferredRenameQueue queue = new DeferredRenameQueue(scheduler);
        Path path = Path.of("/source/file.strm");
        AtomicInteger oldCallback = new AtomicInteger();
        AtomicInteger newCallback = new AtomicInteger();
        Runnable exhausted = mock(Runnable.class);
        assertTrue(queue.defer(path, oldCallback::incrementAndGet, exhausted));
        assertFalse(queue.defer(path, () -> { newCallback.incrementAndGet(); queue.complete(path); }, exhausted));
        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(0, oldCallback.get());
        assertEquals(1, newCallback.get());
        assertTrue(tasks.isEmpty());
        verifyNoInteractions(exhausted);
    }

    @Test
    void 暂时不稳定也会继续重试_达到上限后只报告一次失败() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        Queue<Runnable> tasks = new ArrayDeque<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(i -> {
            tasks.add(i.getArgument(0)); return mock(ScheduledFuture.class);
        });
        DeferredRenameQueue queue = new DeferredRenameQueue(scheduler, 3);
        AtomicInteger attempts = new AtomicInteger();
        Runnable exhausted = mock(Runnable.class);
        queue.defer(Path.of("/source/file.strm"), attempts::incrementAndGet, exhausted);
        while (!tasks.isEmpty()) tasks.remove().run();
        assertEquals(3, attempts.get());
        verify(exhausted, times(1)).run();
        verify(scheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void 停止监控取消计划_已出队回调也不能再执行() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        Queue<Runnable> tasks = new ArrayDeque<>();
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(i -> {
            tasks.add(i.getArgument(0)); return future;
        });
        DeferredRenameQueue queue = new DeferredRenameQueue(scheduler);
        Runnable callback = mock(Runnable.class);
        Runnable exhausted = mock(Runnable.class);
        Path source = Path.of("/source/file.strm");
        queue.defer(source, callback, exhausted);
        queue.close();
        verify(future).cancel(false);
        tasks.remove().run();
        queue.defer(source, callback, exhausted);
        verifyNoInteractions(callback, exhausted);
        assertTrue(tasks.isEmpty());
    }
}
