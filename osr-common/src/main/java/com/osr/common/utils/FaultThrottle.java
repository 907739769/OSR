package com.osr.common.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 周期任务的故障告警节流：<b>状态变化才吵，持续故障闭嘴</b>。
 *
 * <p>周期任务里 {@code catch (Exception e) { log.warn(...) } } 是最自然的写法，但它有个
 * 藏得很深的后果：<b>任何持续性故障都会变成按轮询间隔无限重复的同一条日志</b>。
 * {@code DownloadTrackTask} 每 30 秒一轮，下载器离线一天就是 2880 条逐字相同的 WARN——
 * 它们既淹掉了别的日志，又不比第一条多告诉你任何事。而真正有信息量的只有两个时刻：
 * <b>故障开始</b>和<b>故障恢复</b>。
 *
 * <p>用法（key 用来区分互不相干的故障源，例如下载器 id）：
 * <pre>{@code
 * try {
 *     ...
 *     if (throttle.onSuccess(key)) {
 *         log.info("下载器[{}]已恢复", name);
 *     }
 * } catch (Exception e) {
 *     if (throttle.onFailure(key).shouldReport()) {
 *         log.warn("拉取下载器[{}]失败：{}", name, e.getMessage());
 *     } else {
 *         log.debug("拉取下载器[{}]仍在失败：{}", name, e.getMessage());
 *     }
 * }
 * }</pre>
 *
 * <p><b>持续期间不是彻底静默</b>：每 {@code repeatEvery} 次失败会再放行一条 WARN。
 * 完全静默的代价是「故障两天了，日志里只有最初那一条」——排查的人往回翻不到，会以为它早就好了。
 * 重提时 {@link Decision#consecutiveFailures()} 给出累计次数，调用方应当把它写进日志，
 * 这样一条就能看出故障持续了多久。
 *
 * <p><b>这个类被单例 bean 当实例字段持有是正确的</b>，不违反「{@code @Component} 是单例、
 * 不能用实例字段存状态」那条规矩：那条针对的是<b>单次请求</b>的状态（并发请求会互相覆盖），
 * 而这里存的恰恰是需要跨轮次存活的<b>组件级</b>状态——放进方法局部变量的话每轮都重新开始，
 * 整个节流就不存在了。内部用 ConcurrentHashMap，多个任务线程并发调用是安全的。
 *
 * @author Jack
 */
public class FaultThrottle {

    /** 默认每 120 次失败重提一次；按 30 秒一轮算约等于每小时一条 */
    public static final int DEFAULT_REPEAT_EVERY = 120;

    private final int repeatEvery;
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

    public FaultThrottle() {
        this(DEFAULT_REPEAT_EVERY);
    }

    public FaultThrottle(int repeatEvery) {
        this.repeatEvery = Math.max(1, repeatEvery);
    }

    /**
     * 记一次失败。
     *
     * @return 是否应当输出，以及这个 key 连续失败了多少次（从 1 开始）。
     *         「输出与否」与用什么级别无关——调用方可以拿它门控 WARN（下载器离线），
     *         也可以门控 DEBUG（媒体库里查不到某部剧），两者都是「重复的持续状态」。
     */
    public Decision onFailure(String key) {
        int count = failures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        // 第一次一定要说；之后每 repeatEvery 次提醒一次，别让长期故障彻底消失在日志里
        boolean warn = count == 1 || count % repeatEvery == 0;
        return new Decision(warn, count);
    }

    /**
     * 记一次成功。
     *
     * @return 是否是<b>刚刚从故障中恢复</b>——只有这一次返回 true，调用方据此记一条 INFO。
     *         一直正常的 key 恒返回 false，不会每轮都报「一切正常」。
     */
    public boolean onSuccess(String key) {
        return failures.remove(key) != null;
    }

    /** 当前连续失败次数，0 表示没有正在进行的故障 */
    public int consecutiveFailures(String key) {
        AtomicInteger counter = failures.get(key);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 一次失败的处置结论。
     *
     * @param shouldReport         是否放行本次输出（否则调用方应降级或直接不打）
     * @param consecutiveFailures  该 key 连续失败的次数，从 1 开始
     */
    public record Decision(boolean shouldReport, int consecutiveFailures) {
    }
}
