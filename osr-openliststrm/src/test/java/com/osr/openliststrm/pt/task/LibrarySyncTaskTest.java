package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.pt.media.LibraryRefreshedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提前对账：刷新通知发给媒体服务器之后，等它扫完再提前跑一轮。
 * 守的是三件事：一批通知只多跑一轮（合并）、与定时那轮共用闸门、刚对过账就不重复跑。
 */
class LibrarySyncTaskTest {

    private LibrarySyncService syncService;
    private StuckEpisodeSweepService sweepService;
    private TaskScheduler scheduler;
    private LibrarySyncTask task;
    /** 被安排的任务与时刻，按先后 */
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Instant> scheduledAt = new ArrayList<>();

    @BeforeEach
    void setUp() {
        syncService = mock(LibrarySyncService.class);
        sweepService = mock(StuckEpisodeSweepService.class);
        scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            scheduledAt.add(inv.getArgument(1));
            return null;
        });
        when(syncService.refreshAll()).thenReturn(new LibrarySyncService.SyncOutcome(3, 1, 0));
        task = new LibrarySyncTask();
        ReflectionTestUtils.setField(task, "librarySyncService", syncService);
        ReflectionTestUtils.setField(task, "stuckEpisodeSweepService", sweepService);
        ReflectionTestUtils.setField(task, "scheduler", scheduler);
        ReflectionTestUtils.setField(task, "earlySyncDelaySeconds", 180L);
    }

    private AtomicBoolean running() {
        return (AtomicBoolean) ReflectionTestUtils.getField(task, "running");
    }

    @Test
    void 刷新通知后按延时安排一轮_期间再来的合并进去() {
        Instant before = Instant.now();

        task.onLibraryRefreshed(new LibraryRefreshedEvent(2));
        task.onLibraryRefreshed(new LibraryRefreshedEvent(5));

        assertEquals(1, scheduled.size(), "一批新集只该多跑一轮");
        assertTrue(!scheduledAt.get(0).isBefore(before.plusSeconds(179)), "要等媒体服务器扫完再对账");
    }

    @Test
    void 到点时空闲就跑一轮_含清扫_跑完能再排下一轮() {
        task.onLibraryRefreshed(new LibraryRefreshedEvent(1));

        scheduled.get(0).run();

        verify(syncService).refreshAll();
        verify(sweepService).sweep();
        // 放开了合并标记：这一轮之后落盘的新集要能再排一轮
        task.onLibraryRefreshed(new LibraryRefreshedEvent(1));
        assertEquals(2, scheduled.size());
    }

    /** 与定时那轮共用 running 闸门：正在跑就过一会儿再试，不叠一轮上去 */
    @Test
    void 到点时有一轮在跑_隔一分钟再试_试满就放弃() {
        task.onLibraryRefreshed(new LibraryRefreshedEvent(1));
        running().set(true);

        scheduled.get(0).run();
        for (int i = 1; i <= LibrarySyncTask.EARLY_MAX_RETRIES; i++) {
            assertEquals(i + 1, scheduled.size(), "第 " + i + " 次重试");
            scheduled.get(i).run();
        }

        assertEquals(LibrarySyncTask.EARLY_MAX_RETRIES + 1, scheduled.size(), "试满后不再排");
        verify(syncService, never()).refreshAll();
        // 重试期间合并标记一直占着，放弃时才放开
        task.onLibraryRefreshed(new LibraryRefreshedEvent(1));
        assertEquals(LibrarySyncTask.EARLY_MAX_RETRIES + 2, scheduled.size());
    }

    @Test
    void 重试时轮到空闲就照常跑() {
        task.onLibraryRefreshed(new LibraryRefreshedEvent(1));
        running().set(true);
        scheduled.get(0).run();
        running().set(false);

        scheduled.get(1).run();

        verify(syncService, times(1)).refreshAll();
    }

    /** 定时那轮正好落在媒体服务器扫完之后：刚对过账，提前那轮就是重复劳动 */
    @Test
    void 刚开始过一轮就跳过() {
        task.onLibraryRefreshed(new LibraryRefreshedEvent(1));
        ReflectionTestUtils.setField(task, "lastRoundStartedAt", System.currentTimeMillis() - 10_000);

        scheduled.get(0).run();

        verify(syncService, never()).refreshAll();
        task.onLibraryRefreshed(new LibraryRefreshedEvent(1));
        assertEquals(2, scheduled.size(), "跳过后照样放开合并标记");
    }

    @Test
    void 延时配成0就关闭提前对账() {
        ReflectionTestUtils.setField(task, "earlySyncDelaySeconds", 0L);

        task.onLibraryRefreshed(new LibraryRefreshedEvent(1));

        assertTrue(scheduled.isEmpty());
    }
}
