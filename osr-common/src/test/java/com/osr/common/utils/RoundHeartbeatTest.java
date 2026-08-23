package com.osr.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoundHeartbeat} 的心跳语义。
 */
class RoundHeartbeatTest {

    /** 可手工推进的假时钟，避免测试里真的睡半小时 */
    private static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong();

        long get() {
            return nanos.get();
        }

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    private static RoundHeartbeat beat(FakeClock clock, Duration interval) {
        return new RoundHeartbeat(interval, clock::get);
    }

    @Test
    @DisplayName("第一轮无变化也要报——它是「任务真的跑起来了」的唯一证据")
    void firstQuietRoundAlwaysReports() {
        // 光有 started 只能说明 bean 装配成功，不代表调度器真的触发过一轮
        FakeClock clock = new FakeClock();
        RoundHeartbeat heartbeat = beat(clock, Duration.ofMinutes(30));

        RoundHeartbeat.Beat first = heartbeat.quiet();
        assertTrue(first.shouldReport(), "第一轮必须放行");
        assertEquals(1, first.quietRounds());
    }

    @Test
    @DisplayName("间隔内的后续静默轮闭嘴，到点了再报一次并带上轮数")
    void quietRoundsAreThrottledUntilIntervalElapses() {
        FakeClock clock = new FakeClock();
        RoundHeartbeat heartbeat = beat(clock, Duration.ofMinutes(30));

        heartbeat.quiet();  // 第一轮，已放行

        // 30 秒一轮的任务，半小时内有 59 轮不该出声
        for (int i = 0; i < 59; i++) {
            clock.advance(Duration.ofSeconds(30));
            assertFalse(heartbeat.quiet().shouldReport(), "第 " + (i + 2) + " 轮不该出声");
        }

        clock.advance(Duration.ofSeconds(30));
        RoundHeartbeat.Beat due = heartbeat.quiet();
        assertTrue(due.shouldReport(), "满 30 分钟应当报一次平安");
        assertEquals(60, due.quietRounds(), "要说清这段时间里跑了多少轮，否则看不出它一直在跑");
    }

    @Test
    @DisplayName("有变化后重新计时，紧接着的静默轮不会立刻又报一条")
    void activeResetsTheTimer() {
        // 否则「刚打完一条有内容的 INFO，下一轮马上又来一条『无变化』」，读起来像出了什么事
        FakeClock clock = new FakeClock();
        RoundHeartbeat heartbeat = beat(clock, Duration.ofMinutes(30));

        heartbeat.quiet();
        clock.advance(Duration.ofMinutes(40));
        heartbeat.active();

        clock.advance(Duration.ofMinutes(1));
        RoundHeartbeat.Beat next = heartbeat.quiet();
        assertFalse(next.shouldReport(), "刚说过话，1 分钟后不该再报平安");
        assertEquals(1, next.quietRounds(), "静默轮数应当从有变化那一轮之后重新算");
    }

    @Test
    @DisplayName("报过一次之后轮数归零，下一段独立计数")
    void quietRoundsResetAfterEachBeat() {
        FakeClock clock = new FakeClock();
        RoundHeartbeat heartbeat = beat(clock, Duration.ofMinutes(10));

        heartbeat.quiet();
        clock.advance(Duration.ofMinutes(5));
        heartbeat.quiet();
        clock.advance(Duration.ofMinutes(5));

        RoundHeartbeat.Beat second = heartbeat.quiet();
        assertTrue(second.shouldReport());
        assertEquals(2, second.quietRounds(), "这一段只有 2 轮，不该把上一段的也算进来");
    }

    @Test
    @DisplayName("间隔配成 0 就是每轮都报，不会因为除零之类的原因失效")
    void zeroIntervalReportsEveryRound() {
        FakeClock clock = new FakeClock();
        RoundHeartbeat heartbeat = beat(clock, Duration.ZERO);

        assertTrue(heartbeat.quiet().shouldReport());
        assertTrue(heartbeat.quiet().shouldReport());
    }
}
