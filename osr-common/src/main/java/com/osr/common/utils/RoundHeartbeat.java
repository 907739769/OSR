package com.osr.common.utils;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 周期任务的「跑完一轮」日志节流：<b>有变化立刻说，没变化按间隔报个平安</b>。
 *
 * <p>这是 {@link FaultThrottle} 的反面。那个管的是「出错了别刷屏」，这个管的是
 * <b>「没出错也得让人知道你还活着」</b>——两件事都属于「重复的持续状态」，只是一个是故障、
 * 一个是正常。
 *
 * <p>不加这层节流的话，周期任务只有两种写法，而两种都不能用：
 * <ul>
 *   <li><b>每轮都打</b>：{@code DownloadTrackTask} 每 30 秒一轮，一天 2880 行「本轮无变化」，
 *       把别的日志全淹了；</li>
 *   <li><b>只在出错时打</b>（改造前全项目 14 个周期任务里有 10 个是这样）：正常运行时一行都没有，
 *       于是<b>「一切正常」和「调度器已经死了」在日志上完全一样</b>。实测一整天下来，
 *       每个任务在日志里恰好只有 started 和 stopped 两行。</li>
 * </ul>
 *
 * <p>用法（{@code changed} 是本轮<b>真正发生的业务变化</b>数，不是扫描到的条数——
 * 后者恒大于 0，用它判断等于每轮都打）：
 * <pre>{@code
 * if (changed > 0) {
 *     heartbeat.active();
 *     log.info("对账完成：{} 条订阅，{} 集入库，耗时 {}ms", scanned, changed, cost);
 * } else {
 *     RoundHeartbeat.Beat beat = heartbeat.quiet();
 *     if (beat.shouldReport()) {
 *         log.info("对账完成：{} 条订阅无变化（最近 {} 轮均无变化）", scanned, beat.quietRounds());
 *     }
 * }
 * }</pre>
 *
 * <p><b>心跳按时间而不是按轮数</b>：各任务的间隔从 30 秒到 12 小时不等，按轮数配的话每个调用方
 * 都要自己换算一遍「30 分钟是我的几轮」，改间隔时又必然忘记同步改它。按时间则一律
 * {@code Duration.ofMinutes(30)}，语义就是「最多半小时没消息」。
 *
 * <p><b>第一轮一定报</b>，理由同 {@link FaultThrottle} 的首次失败：启动后第一轮跑通了是最该被
 * 记下的一件事——它证明这个任务不只是 bean 装配成功（那只说明打印了 started），而是真的
 * 跑起来并且跑到了头。
 *
 * <p>与 {@link FaultThrottle} 一样，<b>被单例 bean 当实例字段持有是正确的</b>：存的是需要跨轮次
 * 存活的组件级状态，放进方法局部变量的话每轮都重新开始，整个节流就不存在了。
 *
 * @author Jack
 */
public final class RoundHeartbeat {

    /** 默认最多半小时没消息 */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(30);

    private final long intervalNanos;
    private final LongSupplier clock;

    /** 上次输出（心跳或有变化的那一轮）的时刻；Long.MIN_VALUE 表示还没输出过 */
    private final AtomicLong lastReportNanos = new AtomicLong(Long.MIN_VALUE);

    /** 自上次输出以来，连续多少轮没有任何变化 */
    private final AtomicInteger quietRounds = new AtomicInteger();

    public RoundHeartbeat() {
        this(DEFAULT_INTERVAL);
    }

    public RoundHeartbeat(Duration interval) {
        this(interval, System::nanoTime);
    }

    /** 供测试注入时钟；本类不是 Spring bean，多构造器不会触发「No default constructor found」 */
    RoundHeartbeat(Duration interval, LongSupplier clock) {
        this.intervalNanos = Math.max(0L, interval.toNanos());
        this.clock = clock;
    }

    /**
     * 本轮有实际变化。调用方紧接着自己打一条 INFO——变化的内容各任务不同，
     * 这里不代打，只负责把静默计数清零并把心跳的时间基准推到现在
     * （刚说过话，半小时内不用再报平安）。
     */
    public void active() {
        quietRounds.set(0);
        lastReportNanos.set(clock.getAsLong());
    }

    /**
     * 本轮没有任何变化。
     *
     * @return 是否该报一次平安，以及连续静默了多少轮（含本轮，从 1 开始）。
     *         轮数要写进日志：「最近 120 轮无变化」比「无变化」多说明了这一小时里它一直在跑。
     */
    public Beat quiet() {
        int rounds = quietRounds.incrementAndGet();
        long now = clock.getAsLong();
        long last = lastReportNanos.get();
        // 第一轮（还没输出过任何东西）一定报：它是「这个任务真的跑起来了」的唯一证据
        boolean due = last == Long.MIN_VALUE || now - last >= intervalNanos;
        if (due && lastReportNanos.compareAndSet(last, now)) {
            quietRounds.set(0);
            return new Beat(true, rounds);
        }
        return new Beat(false, rounds);
    }

    /**
     * 一轮静默的处置结论。
     *
     * @param shouldReport 是否放行本次输出
     * @param quietRounds  连续无变化的轮数，从 1 开始
     */
    public record Beat(boolean shouldReport, int quietRounds) {
    }
}
