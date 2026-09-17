package com.osr.openliststrm.helper;

import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.Threads;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.service.IStrmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 异步线程服务
 *
 * @Author Jack
 * @Version 1.1.0
 */
@Service
@Slf4j
public class AsynHelper {

    @Autowired
    private OpenlistApi openlistApi;

    @Autowired
    private IStrmService strmService;

    @Autowired
    private CopyHelper copyHelper;

    @Autowired
    private IOpenlistCopyPlusService openlistCopyPlusService;

    @Autowired
    private OpenlistConfig config;

    @Autowired
    private CopyMonitorRegistry monitorRegistry;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /**
     * 连续多少轮查不到任务状态（OpenList 无响应）才放弃内存监控。
     * <p>
     * 原先一次无响应就把记录标成「未知」并停止监控——可复制任务其实还在跑，一次网络抖动
     * 就让它完成后没人收尾、STRM 也不生成。放弃时<b>不改状态</b>：记录留在处理中，心跳不再续期，
     * 由 {@link CopyRecoveryTask} 接管，它对「OpenList 不可达」是下一轮再看，不会误判。
     */
    static final int MAX_CONSECUTIVE_QUERY_FAILURES = 5;

    /**
     * 判断openlist的复制任务是否完成 完成就执行strm任务 (批量)
     * 改为异步调度模式
     */
    public void isCopyDone(String dstDir, String strmDir) {
        Instant deadline = Instant.now().plus(monitorDuration());
        // 首次延迟30秒后开始检查
        String dstPrefix = StringUtils.removeEnd(dstDir, "/");
        scheduler.schedule(Threads.wrap(() -> {
            try {
                // 仅获取本次目标目录子树下正在进行的任务，避免全表拉取导致多任务并发时互相干扰
                List<OpenlistCopyPlus> copyList = openlistCopyPlusService.lambdaQuery()
                        .eq(OpenlistCopyPlus::getCopyStatus, "1")
                        .likeRight(OpenlistCopyPlus::getCopyDstPath, dstPrefix)
                        .list();

                if (copyList == null || copyList.isEmpty()) {
                    // 如果没有进行中的任务，直接尝试执行收尾逻辑（保持原有业务逻辑）
                    finishStrmDir(dstDir, strmDir);
                    return;
                }

                // 开始递归检查
                processCopyListRecursive(copyList, dstDir, strmDir, deadline, 0, new java.util.HashMap<>());
            } catch (Exception e) {
                log.error("初始化复制完成判定失败", e);
            }
        }), Instant.now().plusSeconds(30));
    }

    /**
     * 递归检查批量任务状态。每轮并行查询所有任务的 copyInfo（原实现为串行，任务多时一轮要
     * 逐个网络往返，收尾很慢），并对重新调度采用退避间隔。
     */
    private void processCopyListRecursive(List<OpenlistCopyPlus> copyList, String dstDir,
                                          String strmDir, Instant deadline, int round,
                                          Map<String, Integer> queryFailures) {
        // 先续心跳再查状态：{@link CopyRecoveryTask} 据此认定这些记录已有内存监控认领，
        // 不会跟这里抢着裁决同一条记录
        monitorRegistry.heartbeat(copyList.stream().map(OpenlistCopyPlus::getCopyTaskId).toList());

        // 并行查询本轮所有任务的状态
        Map<String, JSONObject> infoMap = fetchCopyInfoParallel(copyList);

        Iterator<OpenlistCopyPlus> iterator = copyList.iterator();
        while (iterator.hasNext()) {
            OpenlistCopyPlus copy = iterator.next();
            String taskId = copy.getCopyTaskId();

            if (StringUtils.isBlank(taskId)) {
                iterator.remove();
                continue;
            }

            try {
                JSONObject jsonResponse = infoMap.get(taskId);
                if (jsonResponse == null) {
                    // 无响应不等于任务出了问题，连续多轮才放弃，理由见 MAX_CONSECUTIVE_QUERY_FAILURES
                    int failures = queryFailures.merge(taskId, 1, Integer::sum);
                    if (failures >= MAX_CONSECUTIVE_QUERY_FAILURES) {
                        log.warn("连续 {} 次查询复制任务状态无响应，停止内存监控、交给兜底任务接管: taskId={}, path={}/{}",
                                failures, taskId, copy.getCopySrcPath(), copy.getCopySrcFileName());
                        iterator.remove();
                    }
                    continue;
                }
                queryFailures.remove(taskId);

                // 检查任务状态
                Integer code = jsonResponse.getInteger("code");
                Integer state = -1;
                if (jsonResponse.getJSONObject("data") != null) {
                    state = jsonResponse.getJSONObject("data").getInteger("state");
                }

                // 状态判断逻辑
                if (200 == code && state != 2) {
                    // 状态1是运行中，状态8是等待重试，状态7是失败
                    if (state == 7) {
                        // 源在复制期间被下载器删掉（删种/转移）不是"复制失败"，记成失败只会留下一条
                        // 永远重试不成功的记录 + 一条无从解释的告警，直接丢弃
                        if (!copyHelper.discardIfSourceGone(copy)) {
                            // 失败不重试了
                            updateCopyStatus(copy, "2", CopyFailReason.taskFailed(jsonResponse));
                            notifyCopyFailed(copy);
                        }
                        iterator.remove(); // 移除失败任务
                    }
                    // 其他状态（如1运行中）则保留在列表中继续监控
                } else if (404 == code) {
                    // 任务从 OpenList 的任务表里消失了：看目标文件在不在再下结论。
                    // 目录级的 STRM 由收尾的 finishStrmDir 统一生成，这里不用单独补
                    if (resolveLostTask(copy) != LostTaskOutcome.UNREACHABLE) {
                        iterator.remove();
                    }
                } else if (state == 2) {
                    updateCopyStatus(copy, "3", null);
                    iterator.remove(); // 移除已完成任务
                }
            } catch (Exception e) {
                log.error("查询复制任务状态失败：taskId={}", taskId, e);
            }
        }

        // 检查列表是否为空
        if (copyList.isEmpty()) {
            // 所有任务都已移出列表（完成或失败），执行最终的 strm 生成
            finishStrmDir(dstDir, strmDir);
        } else if (Instant.now().isAfter(deadline)) {
            // 超过最长监控时长仍未结束：强制标记剩余任务为异常，停止继续调度，
            // 避免下游一直卡在非终态时调度任务无限期堆积
            Duration duration = monitorDuration();
            for (OpenlistCopyPlus copy : copyList) {
                updateCopyStatus(copy, "4", CopyFailReason.monitorTimeout(duration));
                log.warn("复制任务监控超时（超过 {}），已标记为异常并停止监控: taskId={}, path={}",
                        duration, copy.getCopyTaskId(), copy.getCopySrcPath());
            }
            TgHelper.sendMsg("<b>复制任务监控超时</b>\n" +
                    "以下批量复制任务超过 " + duration.toMinutes() + " 分钟未结束，已停止监控，请人工核查：\n" +
                    "目标目录：" + StringUtils.escapeHtml(dstDir));
            finishStrmDir(dstDir, strmDir);
        } else {
            // 列表不为空，说明还有任务在运行，按退避间隔再次调用自己
            long interval = nextIntervalSeconds(round);
            scheduler.schedule(Threads.wrap(() -> processCopyListRecursive(copyList, dstDir, strmDir, deadline, round + 1, queryFailures)),
                    Instant.now().plusSeconds(interval));
        }
    }

    /**
     * 并行查询一批复制任务的状态，返回 taskId -> 响应 的映射（响应为空的不放入）。
     */
    private Map<String, JSONObject> fetchCopyInfoParallel(List<OpenlistCopyPlus> copyList) {
        Map<String, JSONObject> infoMap = new ConcurrentHashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = copyList.stream()
                    .map(OpenlistCopyPlus::getCopyTaskId)
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .map(taskId -> CompletableFuture.runAsync(Threads.wrap(() -> {
                        try {
                            JSONObject resp = openlistApi.copyInfo(taskId);
                            if (resp != null) {
                                infoMap.put(taskId, resp);
                            }
                        } catch (Exception e) {
                            log.error("并行查询复制任务状态异常: {}", taskId, e);
                        }
                    }), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        return infoMap;
    }

    /**
     * 轮询退避间隔（秒）：15 → 30 → 60，最高 60s，避免任务多时高频空轮询。
     */
    private long nextIntervalSeconds(int round) {
        long interval = 15L * (1L << Math.min(round, 2));
        return Math.min(interval, 60L);
    }

    /**
     * 单个文件复制监控 (优化版)
     */
    public void isCopyDoneOneFile(String path, OpenlistCopyPlus copy) {
        if (StringUtils.isBlank(copy.getCopyTaskId())) {
            if ("1".equals(config.getOpenListCopyStrm())) {
                strmService.strmOneFile(path, copy.getFileSize());// 生成 STRM 文件
            }
            return;
        }

        // 延迟30秒后开始第一次检查。心跳提前到调度前打，覆盖首检前的这30秒空窗
        monitorRegistry.heartbeat(copy.getCopyTaskId());
        Instant deadline = Instant.now().plus(monitorDuration());
        scheduler.schedule(Threads.wrap(() -> checkOneFileRecursive(path, copy, deadline, 0, 0)), Instant.now().plusSeconds(30));
    }

    /**
     * 递归检查单文件状态
     */
    private void checkOneFileRecursive(String path, OpenlistCopyPlus copy, Instant deadline, int round, int queryFailures) {
        try {
            monitorRegistry.heartbeat(copy.getCopyTaskId());
            JSONObject jsonResponse = openlistApi.copyInfo(copy.getCopyTaskId());

            if (jsonResponse == null) {
                // 无响应不等于任务出了问题，连续多轮才放弃，理由见 MAX_CONSECUTIVE_QUERY_FAILURES
                if (queryFailures + 1 >= MAX_CONSECUTIVE_QUERY_FAILURES) {
                    log.warn("连续 {} 次查询复制任务状态无响应，停止内存监控、交给兜底任务接管: taskId={}, path={}",
                            queryFailures + 1, copy.getCopyTaskId(), path);
                    return;
                }
                scheduleNextCheck(path, copy, deadline, round, queryFailures + 1);
                return;
            }

            Integer code = jsonResponse.getInteger("code");
            Integer state = -1;
            if (jsonResponse.getJSONObject("data") != null) {
                state = jsonResponse.getJSONObject("data").getInteger("state");
            }

            // 判定任务是否完成
            if (404 == code) {
                // 任务从 OpenList 的任务表里消失了：看目标文件在不在再下结论
                LostTaskOutcome outcome = resolveLostTask(copy);
                if (outcome == LostTaskOutcome.SUCCESS && "1".equals(config.getOpenListCopyStrm())) {
                    strmService.strmOneFile(path, copy.getFileSize());
                }
                if (outcome != LostTaskOutcome.UNREACHABLE) {
                    return;
                }
            } else if (state == 2) {
                updateCopyStatus(copy, "3", null);
                // 成功后生成 strm
                if ("1".equals(config.getOpenListCopyStrm())) {
                    strmService.strmOneFile(path, copy.getFileSize());
                }
                return; // 任务完成，退出递归
            } else if (state == 7) {
                // 失败状态。源已被删掉的情况直接丢记录，理由见 CopyHelper#discardIfSourceGone
                if (!copyHelper.discardIfSourceGone(copy)) {
                    updateCopyStatus(copy, "2", CopyFailReason.taskFailed(jsonResponse));
                    notifyCopyFailed(copy);
                }
                return; // 任务失败，退出递归
            }

            if (Instant.now().isAfter(deadline)) {
                // 超过最长监控时长仍未结束：强制标记为异常，停止继续调度
                updateCopyStatus(copy, "4", CopyFailReason.monitorTimeout(monitorDuration()));
                Duration duration = monitorDuration();
                log.warn("单文件复制监控超时（超过 {}），已标记为异常并停止监控: taskId={}, path={}",
                        duration, copy.getCopyTaskId(), path);
                TgHelper.sendMsg("<b>复制任务监控超时</b>\n" +
                        "文件：" + StringUtils.escapeHtml(path) + "\n" +
                        "超过 " + duration.toMinutes() + " 分钟未结束，已停止监控，请人工核查");
                return;
            }

            // 任务仍在运行中，按退避间隔继续调度下一次检查
            scheduleNextCheck(path, copy, deadline, round, 0);

        } catch (Exception e) {
            log.error("递归检查单文件复制状态失败：{}", path, e);
        }
    }

    private void scheduleNextCheck(String path, OpenlistCopyPlus copy, Instant deadline, int round, int queryFailures) {
        long interval = nextIntervalSeconds(round);
        scheduler.schedule(Threads.wrap(() -> checkOneFileRecursive(path, copy, deadline, round + 1, queryFailures)),
                Instant.now().plusSeconds(interval));
    }

    enum LostTaskOutcome { SUCCESS, DST_MISSING, UNREACHABLE }

    /**
     * 任务从 OpenList 任务表消失（404）时的裁决，口径同 {@code CopyRecoveryTask#probeDst}。
     * <p>
     * 原先 404 一律标成「未知」。可 404 最常见的两个成因——OpenList 重启、用户在 OpenList 里
     * 清掉了已完成的任务——多半意味着文件<b>早就复制完了</b>，标成未知等于把一条成功的记录报成异常。
     * 目标文件在就记成功；不在才记未知；查目标文件时 OpenList 也无响应就先不下结论，
     * 留给下一轮（或兜底任务）。
     */
    LostTaskOutcome resolveLostTask(OpenlistCopyPlus copy) {
        String dstFile = StringUtils.removeEnd(copy.getCopyDstPath(), "/") + "/" + copy.getCopyDstFileName();
        JSONObject resp = openlistApi.getFile(dstFile);
        if (resp == null) {
            return LostTaskOutcome.UNREACHABLE;
        }
        if (Integer.valueOf(200).equals(resp.getInteger("code")) && resp.getJSONObject("data") != null) {
            log.info("复制任务已从 OpenList 任务列表消失，但目标文件已存在，记为成功: {}", dstFile);
            updateCopyStatus(copy, "3", null);
            return LostTaskOutcome.SUCCESS;
        }
        updateCopyStatus(copy, "4", CopyFailReason.taskLostAndDstMissing());
        return LostTaskOutcome.DST_MISSING;
    }

    /** 失败通知带上原因：原因已经落库，不带的话用户收到通知后唯一能做的是打开页面再查一遍 */
    private void notifyCopyFailed(OpenlistCopyPlus copy) {
        TgHelper.sendMsg("<b>复制任务失败</b>\n" +
                "源目录：" + StringUtils.escapeHtml(copy.getCopySrcPath()) + "\n" +
                "源文件名：" + StringUtils.escapeHtml(copy.getCopySrcFileName()) +
                (StringUtils.isBlank(copy.getFailReason()) ? "" : "\n原因：" + StringUtils.escapeHtml(copy.getFailReason())));
    }

    // 辅助方法：复制任务状态监控的最长持续时间（可通过 sys_config 配置）
    private Duration monitorDuration() {
        return Duration.ofMinutes(config.getCopyMonitorMaxMinutes());
    }

    // 辅助方法：更新数据库状态
    // 状态与原因一起写：fail_reason 是 ALWAYS 更新策略，成功时传 null 即清空（见 OpenlistCopyPlus#failReason）
    private void updateCopyStatus(OpenlistCopyPlus copy, String status, String failReason) {
        copy.setCopyStatus(status);
        copy.setFailReason(failReason);
        openlistCopyPlusService.updateById(copy);
    }

    // 辅助方法：处理目录 strm 生成逻辑
    private void finishStrmDir(String dstDir, String strmDir) {
        if (!"1".equals(config.getOpenListCopyStrm())) {
            return;
        }
        try {
            String newStrmDir = strmDir;
            if (strmDir.startsWith("/")) {
                newStrmDir = strmDir.replaceFirst("/", "");
            }
            String newDstDir = dstDir;
            if (dstDir.endsWith("/")) {
                newDstDir = dstDir.substring(0, dstDir.lastIndexOf("/"));
            }
            strmService.strmDir(newDstDir + "/" + newStrmDir); // 生成 STRM 文件
        } catch (Exception e) {
            log.error("为目录生成 STRM 失败：{}", dstDir, e);
        }
    }
}