package com.ruoyi.openliststrm.pt.indexer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 索引器请求的全局限流器：所有打向 Torznab 的 HTTP 请求（RSS 轮询、关键词搜索、ID 精确搜索、
 * caps 探测）都必须经由本类，是唯一的节流入口。
 * <p>
 * <b>为什么必须是全局单例</b>：改造前 {@code RssPollService.poll()} 与
 * {@code SearchSupplementService.searchAcrossIndexers()/searchByExternalId()} 各自
 * {@code new Semaphore(n)}，且后两者是<b>每次调用</b>新建。这些信号量彼此毫不相干，
 * 真实并发上限是 {@code 4 + 3 × 同时进行的搜索次数}，无界——用户连点几次搜索、
 * 批量建订阅触发的异步补搜、RSS 轮询三者叠加时会瞬间打穿 Prowlarr 直达后端 PT 站点。
 * </p>
 * <p>
 * <b>为什么限"间隔"而不只限"并发"</b>：并发数限的是"同时几个"，NexusPHP 的 rate limit
 * 封的是"单位时间几个"。一次订阅补搜要打 4 轮（ID + 中文 + 英文 + 原语言），每轮对每个
 * 索引器各一次请求——10 个索引器就是 40 个请求，并发限 3 也挡不住它们在两秒内全部发完。
 * 因此本类对<b>每个索引器</b>做串行化 + 最小请求间隔，对<b>全部索引器</b>做并发上限。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class IndexerRateLimiter {

    /** 单个索引器的槽位：串行许可证 + 下一次允许发起请求的时间点 */
    private static final class Slot {
        /** 公平信号量：保证等待中的请求按到达顺序放行，避免高频轮询饿死用户手动搜索 */
        private final Semaphore serial = new Semaphore(1, true);
        /**
         * 用 AtomicLong 而非 volatile long：写入方有两处（请求结束的最小间隔、429 冷却惩罚），
         * 且两者都是「取较大值」语义。裸的读-比较-写在并发下会让后完成的最小间隔覆盖掉
         * 另一线程刚设好的长冷却，等于静默取消了退避。
         */
        private final AtomicLong nextAllowedAt = new AtomicLong();

        /** 把下一次允许时间推迟到至少 {@code millis}，已经更晚则保持不变 */
        void pushBackTo(long millis) {
            nextAllowedAt.accumulateAndGet(millis, Math::max);
        }

        long nextAllowedAt() {
            return nextAllowedAt.get();
        }
    }

    /** 同一索引器两次请求之间的最小间隔（毫秒） */
    private final long minIntervalMillis;

    /**
     * 任何一段等待（站点串行许可、最小间隔、全局并发许可）超过该时长即放弃本次请求。
     * 用户手动搜索不能因为别人的请求排在前面而卡住几分钟。
     */
    private final long maxWaitMillis;

    /** 跨所有索引器的全局并发上限 */
    private final Semaphore globalPermits;

    private final ConcurrentMap<Integer, Slot> slots = new ConcurrentHashMap<>();

    public IndexerRateLimiter(
            @Value("${pt.indexer.min-request-interval-ms:2000}") long minIntervalMillis,
            @Value("${pt.indexer.max-wait-ms:30000}") long maxWaitMillis,
            @Value("${pt.indexer.global-concurrency:4}") int globalConcurrency) {
        this.minIntervalMillis = Math.max(0L, minIntervalMillis);
        this.maxWaitMillis = Math.max(0L, maxWaitMillis);
        this.globalPermits = new Semaphore(Math.max(1, globalConcurrency), true);
    }

    /** 受限执行的动作，允许抛 {@link IOException}（HTTP 调用的自然签名） */
    @FunctionalInterface
    public interface IndexerCall<T> {
        T call() throws IOException;
    }

    /**
     * 在限流保护下执行一次索引器请求。
     * <p>
     * 顺序刻意是「先抢该索引器的串行许可 → 再等最小间隔 → 最后抢全局并发许可」：
     * 等待间隔的那段时间不占用全局许可，否则一个处于冷却中的索引器会白白堵住其它索引器的名额。
     * </p>
     * <p>
     * <b>三段等待全部受 {@code maxWaitMillis} 约束</b>，超时一律快速失败。这一点必须成立：
     * 轮询的读超时是 60 秒，若串行许可是无限等待，用户此刻手动搜同一个索引器就会死等到
     * 前一个请求结束，前端 60 秒超时正好踩上；队列一长还会更久。调用方（轮询、搜索）
     * 都能容忍单次请求被跳过，但不能容忍线程被别人的请求占住。
     * </p>
     *
     * @throws IOException            动作本身抛出的异常，或任一段等待超过 {@code maxWaitMillis} 的快速失败
     * @throws InterruptedIOException 等待许可期间被中断（中断标志已恢复）
     */
    public <T> T execute(Integer indexerId, IndexerCall<T> action) throws IOException {
        Slot slot = slots.computeIfAbsent(indexerId == null ? -1 : indexerId, k -> new Slot());
        acquire(slot.serial, "索引器[" + indexerId + "]的串行许可（前一个请求尚未结束）");
        try {
            awaitNextAllowed(indexerId, slot);
            acquire(globalPermits, "全局并发许可（其它索引器正占满名额）");
            try {
                return action.call();
            } finally {
                globalPermits.release();
            }
        } finally {
            // 无论成功失败都从「本次请求结束」开始计时：失败请求同样消耗了对方的配额
            slot.pushBackTo(System.currentTimeMillis() + minIntervalMillis);
            slot.serial.release();
        }
    }

    /**
     * 给某个索引器施加一段冷却：命中 429/503 后由调用方按 {@code Retry-After} 调用。
     * <p>
     * 冷却记在限流器而非某个任务里，因此对<b>所有</b>入口一并生效——RSS 轮询被限流后，
     * 用户此时手动发起的搜索同样会被挡在冷却期外，不会绕过退避继续捅同一个站点。
     * </p>
     *
     * @param cooldownSeconds 冷却秒数，&lt;=0 时忽略
     */
    public void penalize(Integer indexerId, long cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return;
        }
        Slot slot = slots.computeIfAbsent(indexerId == null ? -1 : indexerId, k -> new Slot());
        // 取较大值：多个并发请求同时撞限流时，保留最长的那段冷却，不能被后到的短冷却缩短
        slot.pushBackTo(System.currentTimeMillis() + cooldownSeconds * 1000L);
        log.warn("索引器[{}]进入限流冷却，{} 秒内不再发起请求", indexerId, cooldownSeconds);
    }

    /** 剩余冷却毫秒数，主要供测试与排查用；无冷却时返回 0 */
    public long remainingCooldownMillis(Integer indexerId) {
        Slot slot = slots.get(indexerId == null ? -1 : indexerId);
        if (slot == null) {
            return 0L;
        }
        return Math.max(0L, slot.nextAllowedAt() - System.currentTimeMillis());
    }

    private void awaitNextAllowed(Integer indexerId, Slot slot) throws IOException {
        long waitMillis = slot.nextAllowedAt() - System.currentTimeMillis();
        if (waitMillis <= 0) {
            return;
        }
        if (waitMillis > maxWaitMillis) {
            // 快速失败而非挂死：调用方（轮询/搜索）都能容忍单次跳过，但不能容忍线程被冷却期占住
            throw new IOException("索引器处于限流冷却中，还需等待 " + (waitMillis / 1000) + " 秒，本次请求跳过");
        }
        try {
            // 虚拟线程下 sleep 会让出载体线程，不占用平台线程资源
            Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("等待索引器[" + indexerId + "]限流间隔时被中断");
        }
    }

    /**
     * 带上限地抢一个许可。等不到不是异常状态而是正常的背压结果——本次请求跳过，
     * 调用方记一条日志继续跑，比让线程无限期挂在队列里健康得多。
     *
     * @param what 超时消息里用来说明等的是哪一段，便于排查是站点串行还是全局名额被占满
     */
    private void acquire(Semaphore semaphore, String what) throws IOException {
        try {
            if (!semaphore.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS)) {
                throw new IOException("等待" + what + "超过 " + maxWaitMillis + "ms，本次请求跳过");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("等待索引器限流许可时被中断");
        }
    }
}
