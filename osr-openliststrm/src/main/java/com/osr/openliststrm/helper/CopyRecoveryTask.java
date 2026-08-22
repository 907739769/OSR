package com.osr.openliststrm.helper;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.service.IStrmService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 复制任务监控的兜底扫描。
 *
 * <p>{@link AsynHelper} 的监控链只活在内存里（{@code scheduler.schedule} 递归自调），
 * 进程一重启整条链就断掉：库里还是「处理中」的复制记录再没人查状态，既不会转终态，
 * 也不会触发 STRM 生成。用户看到的现象就是「服务重启后同步任务卡住了，状态一直是处理中」。
 * 内存里的 deadline 同样随进程消失，所以连超时兜底都不会发生，那些记录会永远停在处理中。
 *
 * <p>重启丢的其实是两段：
 * <ol>
 *   <li><b>还在处理中的记录没人查状态</b>——本任务每 {@link #SWEEP_INTERVAL} 扫一遍
 *       {@code copy_status='1'} 且没有内存监控认领（见 {@link CopyMonitorRegistry}）的记录，重新裁决；</li>
 *   <li><b>重启前就复制完、但 STRM 还没生成的记录</b>——批量同步的 STRM 是整批收尾时由
 *       {@code AsynHelper#finishStrmDir} 一次性生成的，重启时这一步还没轮到，那些已经是「成功」的记录
 *       就永远等不到自己的 .strm 了。这一段由 {@link #backfillMissingStrm()} 在进程启动后补一次。</li>
 * </ol>
 *
 * <p>执行顺序上第二段<b>排在第一段前面</b>，理由见 {@link #sweep()} 里的注释：两段的判据会重叠，
 * 反过来跑会把本轮刚判成功的记录再补一遍 STRM。
 *
 * <p>第一段的裁决口径：
 * <ul>
 *   <li>AList 还认得这个任务 → 按 state 判定，成功就补上重启时漏掉的 STRM 生成；</li>
 *   <li>AList 返回 404（它自己也重启过、任务表已清空）或记录压根没有 taskId
 *       → 退回到唯一的硬证据：<b>目标文件在不在</b>。在就是成功，这正是重启场景下最常见的分支
 *       ——文件早复制完了，只是没人来收尾；</li>
 *   <li>AList 不可达 → 本轮不下结论，留给下一轮，避免一次网络抖动把一批记录误判成异常；</li>
 *   <li>确实还在跑 → 什么也不做，下一轮再看，直到超过最长监控时长才强制收敛为「未知」。</li>
 * </ul>
 *
 * <p>扫描周期固定 5 分钟：这是兜底，不是主监控路径（主路径是 {@code AsynHelper} 的 15/30/60 秒退避），
 * 扫太密只会在没有故障时白白打 AList。
 *
 * @author Jack
 */
@Slf4j
@Component
public class CopyRecoveryTask {

    /** 兜底扫描周期 */
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

    /**
     * 记录刚落库到内存监控首次续心跳之间有个空窗（{@code AsynHelper} 首检延迟 30 秒），
     * 这段时间内的记录不能算无主。宽限期取 3 分钟，足够覆盖首检延迟加上一轮批量查询的耗时。
     */
    private static final Duration GRACE = Duration.ofMinutes(3);

    /**
     * STRM 补生成的回溯窗口。补生成解决的是「重启打断了整批收尾」，时间上紧挨着停机，
     * 给 7 天足够覆盖「崩了几天才被发现」；再往前翻既无意义，又要让 NOT EXISTS 扫过整张历史表。
     */
    private static final Duration BACKFILL_LOOKBACK = Duration.ofDays(7);

    /** 单轮最多处理的记录数，避免历史遗留的一大批记录在一轮里打爆 AList */
    private static final int MAX_PER_ROUND = 200;

    /** 单轮并发探测的上限，与 AList 的承受能力对齐 */
    private static final int PROBE_CONCURRENCY = 8;

    /** 汇总通知里最多列出的记录条数，其余只给计数 */
    private static final int NOTIFY_SAMPLE_LIMIT = 5;

    /** MyBatis-Plus 自动填充写入的时间格式，见 {@code MyMetaObjectHandler} */
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private OpenlistApi openlistApi;
    @Autowired
    private IStrmService strmService;
    @Autowired
    private IOpenlistCopyPlusService openlistCopyPlusService;
    @Autowired
    private OpenlistConfig config;
    @Autowired
    private OpenListHelper openListHelper;
    @Autowired
    private CopyHelper copyHelper;
    @Autowired
    private CopyMonitorRegistry monitorRegistry;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过扫描周期时，避免重叠触发重复裁决同一批记录 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** STRM 补生成只针对「重启打断收尾」，每个进程跑一次即可，不必每轮都扫一遍历史记录 */
    private final AtomicBoolean backfillDone = new AtomicBoolean(false);

    /** 一条记录本轮的裁决结果 */
    private enum Outcome {
        /** 已判定完成，STRM 已补 */
        SUCCESS,
        /** 已判定失败 */
        FAILED,
        /** 复制失败但源文件已不在（下载器删种），记录已丢弃——不算失败，也不进通知 */
        DISCARDED,
        /** 超时或证据不足，强制收敛为未知 */
        UNKNOWN,
        /** 本轮不下结论，留给下一轮 */
        PENDING
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        // 首轮延迟 1 分钟：让启动阶段的同步任务先把自己的内存监控挂上，避免刚启动就抢活
        scheduler.scheduleAtFixedRate(Threads.wrap(this::sweep), Instant.now().plusSeconds(60), SWEEP_INTERVAL);
        log.info("CopyRecoveryTask started, 兜底扫描周期={}", SWEEP_INTERVAL);
    }

    @PreDestroy
    public void stop() {
        log.info("CopyRecoveryTask stopped");
        MDC.clear();
    }

    private void sweep() {
        if (!running.compareAndSet(false, true)) {
            log.debug("CopyRecoveryTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            monitorRegistry.evictExpired();
            // 补生成必须排在接管之前：它认的是「已经是成功态、却没有 STRM 记录」，
            // 而 sweepPendingCopies 判成功的记录同样满足这个形状。反过来的顺序会让本轮刚判成功、
            // STRM 记录也已生成的那批被 backfill 立刻再捞一次（NOT EXISTS 读到的还是没有该行的快照），
            // 同一个文件生成两遍、留下两条一模一样的 openlist_strm 记录——表上没有唯一约束兜底。
            // 先跑 backfill 时，它看到的只有重启前遗留的记录，正是它要解决的那一段。
            if (backfillDone.compareAndSet(false, true)) {
                backfillMissingStrm();
            }
            sweepPendingCopies();
        } catch (Exception e) {
            log.error("CopyRecoveryTask sweep error", e);
        } finally {
            running.set(false);
        }
    }

    /** 第一段：接管没有内存监控认领的「处理中」记录 */
    private void sweepPendingCopies() {
        List<OpenlistCopyPlus> pending = openlistCopyPlusService.lambdaQuery()
                .eq(OpenlistCopyPlus::getCopyStatus, "1")
                .orderByAsc(OpenlistCopyPlus::getCopyId)
                .last("LIMIT " + MAX_PER_ROUND)
                .list();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        if (pending.size() == MAX_PER_ROUND) {
            // 不静默截断：本轮只处理了一批，剩下的下一轮继续，日志说清楚以免读成「已经扫全了」
            log.info("兜底扫描本轮达到单轮上限 {} 条，剩余处理中记录留待下一轮", MAX_PER_ROUND);
        }

        Instant now = Instant.now();
        List<OpenlistCopyPlus> orphans = pending.stream()
                .filter(copy -> !monitorRegistry.monitoredInMemory(copy.getCopyTaskId()))
                .filter(copy -> !inGrace(copy, now))
                .toList();
        if (orphans.isEmpty()) {
            log.debug("兜底扫描：{} 条处理中记录均有内存监控认领或仍在宽限期内", pending.size());
            return;
        }

        log.info("兜底扫描发现 {} 条无人监控的复制记录（处理中共 {} 条），开始重新裁决",
                orphans.size(), pending.size());
        resolveAll(orphans);
    }

    /**
     * 第二段：给「已复制成功但没有 STRM 记录」的文件补生成。
     *
     * <p>批量同步的 STRM 由整批收尾时的 {@code AsynHelper#finishStrmDir} 统一生成，
     * 进程在收尾前退出的话，那批已经标成功的记录就再没有别的入口会去生成它们的 .strm。
     * 判据用「{@code openlist_strm} 里没有对应行」而不是「文件在不在磁盘上」：
     * 生成失败也会留下一条 {@code strm_status='0'} 的记录，那属于 STRM 重试功能的范围，
     * 这里不重复插手。
     */
    private void backfillMissingStrm() {
        if (!"1".equals(config.getOpenListCopyStrm())) {
            return;
        }
        String since = LocalDateTime.now().minus(BACKFILL_LOOKBACK).format(TS_FORMAT);
        List<OpenlistCopyPlus> missing = openlistCopyPlusService.list(new QueryWrapper<OpenlistCopyPlus>()
                .eq("copy_status", "3")
                .ge("update_time", since)
                .notExists("SELECT 1 FROM openlist_strm s WHERE s.strm_path = openlist_copy.copy_dst_path"
                        + " AND s.strm_file_name = openlist_copy.copy_dst_file_name")
                .orderByDesc("copy_id")
                .last("LIMIT " + MAX_PER_ROUND));
        if (missing == null || missing.isEmpty()) {
            return;
        }
        if (missing.size() == MAX_PER_ROUND) {
            log.info("STRM 补生成达到单轮上限 {} 条，本次只补这一批（如仍有缺口，下次重启会继续）", MAX_PER_ROUND);
        }
        log.info("发现 {} 条已复制成功但缺少 STRM 的记录，开始补生成", missing.size());

        AtomicInteger done = new AtomicInteger();
        runBounded(missing, copy -> {
            if (generateStrm(copy)) {
                done.incrementAndGet();
            }
        });
        log.info("STRM 补生成完成：{}/{}", done.get(), missing.size());
    }

    /**
     * 并发裁决一批无主记录，并把结果汇总成一条通知。
     * <p>失败/未知<b>不逐条发通知</b>：升级后首轮很可能一次性捞出几十上百条历史遗留记录，
     * 逐条发会把用户的 TG/企微刷屏。
     */
    private void resolveAll(List<OpenlistCopyPlus> orphans) {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger unknown = new AtomicInteger();
        AtomicInteger pending = new AtomicInteger();
        AtomicInteger discarded = new AtomicInteger();
        Map<String, String> samples = new ConcurrentHashMap<>();

        runBounded(orphans, copy -> {
            switch (resolveOne(copy)) {
                case SUCCESS -> success.incrementAndGet();
                case FAILED -> {
                    failed.incrementAndGet();
                    collectSample(samples, copy);
                }
                case DISCARDED -> discarded.incrementAndGet();
                case UNKNOWN -> {
                    unknown.incrementAndGet();
                    collectSample(samples, copy);
                }
                case PENDING -> pending.incrementAndGet();
            }
        });

        log.info("兜底扫描完成：成功 {}，失败 {}，源已消失丢弃 {}，未知 {}，本轮未决 {}",
                success.get(), failed.get(), discarded.get(), unknown.get(), pending.get());
        notifySummary(success.get(), failed.get(), unknown.get(), samples);
    }

    /**
     * 裁决单条记录。
     */
    private Outcome resolveOne(OpenlistCopyPlus copy) {
        boolean expired = isExpired(copy);
        String taskId = copy.getCopyTaskId();

        if (StringUtils.isBlank(taskId)) {
            // 没有任务 ID 就无从查询状态（提交复制时 AList 没回 tasks），只能看目标文件
            return probeDst(copy, expired);
        }

        JSONObject resp = openlistApi.copyInfo(taskId);
        if (resp == null) {
            // AList 不可达：一次网络抖动不该把一批记录判成异常，留给下一轮；已超时的才强制收敛
            return expired ? probeDst(copy, true) : Outcome.PENDING;
        }

        Integer code = resp.getInteger("code");
        JSONObject data = resp.getJSONObject("data");
        Integer state = data == null ? null : data.getInteger("state");

        if (Integer.valueOf(404).equals(code)) {
            // 任务已从 AList 的任务表消失（AList 重启或任务列表被清理）。
            // 重启场景下这是最常见的分支：文件多半早就复制完了，只是没人来收尾
            return probeDst(copy, expired);
        }
        if (state != null && state == 2) {
            return markSuccess(copy);
        }
        if (state != null && state == 7) {
            // 与 AsynHelper 同一口径：源在复制期间被下载器删掉的，丢记录而不是记失败
            if (copyHelper.discardIfSourceGone(copy)) {
                return Outcome.DISCARDED;
            }
            markStatus(copy, "2");
            log.info("兜底扫描判定复制失败: {}/{}", copy.getCopySrcPath(), copy.getCopySrcFileName());
            return Outcome.FAILED;
        }
        // 仍在运行（state=1 运行中、8 等待重试）或 AList 返回了意料外的响应：下一轮再看
        if (expired) {
            markStatus(copy, "4");
            log.warn("复制任务超过最长监控时长（{}）仍未结束，标记为异常: taskId={}, path={}/{}",
                    monitorDuration(), taskId, copy.getCopySrcPath(), copy.getCopySrcFileName());
            return Outcome.UNKNOWN;
        }
        return Outcome.PENDING;
    }

    /**
     * 查不到任务状态时的裁决依据：目标文件在不在。
     * <p>目标文件已存在就等价于复制成功——这也是 {@code CopyServiceImpl#syncOneFile} 判「已存在直接记成功」
     * 用的同一条判据，兜底扫描没有理由比它更严格。
     */
    private Outcome probeDst(OpenlistCopyPlus copy, boolean expired) {
        String dstFile = dstFilePath(copy);
        if (dstFile != null) {
            JSONObject resp = openlistApi.getFile(dstFile);
            if (resp != null && Integer.valueOf(200).equals(resp.getInteger("code"))
                    && resp.getJSONObject("data") != null) {
                return markSuccess(copy);
            }
        }
        if (expired) {
            markStatus(copy, "4");
            log.warn("复制任务状态不可考且目标文件不存在，标记为异常: taskId={}, dst={}",
                    copy.getCopyTaskId(), dstFile);
            return Outcome.UNKNOWN;
        }
        return Outcome.PENDING;
    }

    /** 标记成功并补上重启时漏掉的 STRM 生成 */
    private Outcome markSuccess(OpenlistCopyPlus copy) {
        markStatus(copy, "3");
        if ("1".equals(config.getOpenListCopyStrm())) {
            generateStrm(copy);
        }
        return Outcome.SUCCESS;
    }

    /**
     * 给单条复制记录生成 STRM。
     *
     * <p>用 {@code strmOneFile} 而不是目录级 {@code strmDir}：兜底恢复是逐条记录来的，
     * 拿不到当初那次同步的根目录与相对路径（内存里的参数已随进程消失），而记录本身足够定位到这一个文件。
     * {@code strmOneFile} 内部按 {@code existsStrm} 去重，与内存链重复触发也不会写两次。
     *
     * <p>视频类型仍要判一次：目录级生成（{@code StrmServiceImpl#getData}）是按视频扩展名过滤的，
     * 逐文件恢复不判的话，用户改过视频扩展名配置之后两条路径会给出不一样的结果。
     */
    private boolean generateStrm(OpenlistCopyPlus copy) {
        String dstFile = dstFilePath(copy);
        if (dstFile == null || !openListHelper.isVideo(copy.getCopyDstFileName())) {
            return false;
        }
        try {
            strmService.strmOneFile(dstFile);
            return true;
        } catch (Exception e) {
            // STRM 生成失败不回滚复制状态：复制确实成功了，STRM 有自己的失败记录与重试入口
            log.error("兜底补生成 STRM 失败: {}", dstFile, e);
            return false;
        }
    }

    private void markStatus(OpenlistCopyPlus copy, String status) {
        copy.setCopyStatus(status);
        openlistCopyPlusService.updateById(copy);
    }

    private String dstFilePath(OpenlistCopyPlus copy) {
        String dir = StringUtils.removeEnd(copy.getCopyDstPath(), "/");
        if (StringUtils.isBlank(dir) || StringUtils.isBlank(copy.getCopyDstFileName())) {
            return null;
        }
        return dir + "/" + copy.getCopyDstFileName();
    }

    /** 以固定并发跑一批记录，单条异常不影响其余记录 */
    private void runBounded(List<OpenlistCopyPlus> items, Consumer<OpenlistCopyPlus> action) {
        Semaphore permits = new Semaphore(PROBE_CONCURRENCY);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = items.stream()
                    .map(copy -> CompletableFuture.runAsync(Threads.wrap(() -> {
                        try {
                            permits.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            action.accept(copy);
                        } catch (Exception e) {
                            log.error("兜底处理复制记录异常: copyId={}, taskId={}",
                                    copy.getCopyId(), copy.getCopyTaskId(), e);
                        } finally {
                            permits.release();
                        }
                    }), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    /** 记录是否还在宽限期内（刚落库、内存监控还没来得及续第一次心跳） */
    private boolean inGrace(OpenlistCopyPlus copy, Instant now) {
        Instant startedAt = parseStartedAt(copy);
        return startedAt != null && startedAt.plus(GRACE).isAfter(now);
    }

    /**
     * 是否已超过最长监控时长。时间戳解析不出来（历史脏数据）时按已超时处理——
     * 这类记录没有任何办法判断年龄，留着只会永远占在「处理中」里，
     * 而 {@link #probeDst} 仍会先给它一次「目标文件在不在」的机会。
     */
    private boolean isExpired(OpenlistCopyPlus copy) {
        Instant startedAt = parseStartedAt(copy);
        return startedAt == null || Instant.now().isAfter(startedAt.plus(monitorDuration()));
    }

    /**
     * 记录本次复制的起算时间：优先 update_time（批量同步会复用旧记录并在提交复制时更新它），
     * 退回 create_time。
     */
    private Instant parseStartedAt(OpenlistCopyPlus copy) {
        String ts = StringUtils.isNotBlank(copy.getUpdateTime()) ? copy.getUpdateTime() : copy.getCreateTime();
        if (StringUtils.isBlank(ts)) {
            return null;
        }
        // 实体字段是 String，不同 JDBC 驱动对 datetime 的字符串化可能带 'T' 或小数秒，统一裁到秒
        String normalized = ts.trim().replace('T', ' ');
        if (normalized.length() > 19) {
            normalized = normalized.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(normalized, TS_FORMAT).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            log.debug("复制记录时间戳无法解析: copyId={}, value={}", copy.getCopyId(), ts);
            return null;
        }
    }

    private Duration monitorDuration() {
        return Duration.ofMinutes(config.getCopyMonitorMaxMinutes());
    }

    private void collectSample(Map<String, String> samples, OpenlistCopyPlus copy) {
        if (samples.size() >= NOTIFY_SAMPLE_LIMIT) {
            return;
        }
        samples.putIfAbsent(String.valueOf(copy.getCopyId()),
                StringUtils.escapeHtml(copy.getCopySrcPath() + "/" + copy.getCopySrcFileName()));
    }

    private void notifySummary(int success, int failed, int unknown, Map<String, String> samples) {
        if (failed == 0 && unknown == 0) {
            // 全部恢复成功属于正常自愈，没必要打扰用户
            return;
        }
        StringBuilder msg = new StringBuilder("<b>复制任务兜底恢复</b>\n")
                .append("接管了无人监控的复制记录，本轮结果：\n")
                .append("已完成：").append(success).append("\n")
                .append("失败：").append(failed).append("\n")
                .append("异常（超时或状态不可考）：").append(unknown).append("\n");
        if (!samples.isEmpty()) {
            msg.append("示例：\n");
            samples.values().forEach(s -> msg.append("· ").append(s).append("\n"));
        }
        TgHelper.sendMsg(msg.toString());
    }
}
