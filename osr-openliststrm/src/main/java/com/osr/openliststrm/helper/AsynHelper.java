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
                processCopyListRecursive(copyList, dstDir, strmDir, deadline, 0);
            } catch (Exception e) {
                log.error("Error in isCopyDone initialization", e);
            }
        }), Instant.now().plusSeconds(30));
    }

    /**
     * 递归检查批量任务状态。每轮并行查询所有任务的 copyInfo（原实现为串行，任务多时一轮要
     * 逐个网络往返，收尾很慢），并对重新调度采用退避间隔。
     */
    private void processCopyListRecursive(List<OpenlistCopyPlus> copyList, String dstDir,
                                          String strmDir, Instant deadline, int round) {
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
                    // API 请求失败或无响应，视为异常结束
                    updateCopyStatus(copy, "4");
                    iterator.remove(); // 从监控列表中移除
                    continue;
                }

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
                        // 失败不重试了
                        updateCopyStatus(copy, "2");
                        TgHelper.sendMsg("<b>复制任务失败</b>\n" +
                                "源目录：" + StringUtils.escapeHtml(copy.getCopySrcPath()) + "\n" +
                                "源文件名：" + StringUtils.escapeHtml(copy.getCopySrcFileName()));
                        iterator.remove(); // 移除失败任务
                    }
                    // 其他状态（如1运行中）则保留在列表中继续监控
                } else if (404 == code || state == 2) {
                    // 404: 任务丢失/过期? state=2: 完成
                    if (404 == code) {
                        updateCopyStatus(copy, "4");
                    }
                    if (state == 2) {
                        updateCopyStatus(copy, "3");
                    }
                    iterator.remove(); // 移除已完成任务
                }
            } catch (Exception e) {
                log.error("Error checking task status for id: {}", taskId, e);
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
                updateCopyStatus(copy, "4");
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
            scheduler.schedule(Threads.wrap(() -> processCopyListRecursive(copyList, dstDir, strmDir, deadline, round + 1)),
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
                strmService.strmOneFile(path);// 生成 STRM 文件
            }
            return;
        }

        // 延迟30秒后开始第一次检查。心跳提前到调度前打，覆盖首检前的这30秒空窗
        monitorRegistry.heartbeat(copy.getCopyTaskId());
        Instant deadline = Instant.now().plus(monitorDuration());
        scheduler.schedule(Threads.wrap(() -> checkOneFileRecursive(path, copy, deadline, 0)), Instant.now().plusSeconds(30));
    }

    /**
     * 递归检查单文件状态
     */
    private void checkOneFileRecursive(String path, OpenlistCopyPlus copy, Instant deadline, int round) {
        try {
            monitorRegistry.heartbeat(copy.getCopyTaskId());
            JSONObject jsonResponse = openlistApi.copyInfo(copy.getCopyTaskId());

            if (jsonResponse == null) {
                updateCopyStatus(copy, "4");
                return; // 结束监控
            }

            Integer code = jsonResponse.getInteger("code");
            Integer state = -1;
            if (jsonResponse.getJSONObject("data") != null) {
                state = jsonResponse.getJSONObject("data").getInteger("state");
            }

            // 判定任务是否完成
            if (404 == code || state == 2) {
                if (404 == code) {
                    updateCopyStatus(copy, "4");
                }
                if (state == 2) {
                    updateCopyStatus(copy, "3");
                    // 成功后生成 strm
                    if ("1".equals(config.getOpenListCopyStrm())) {
                        strmService.strmOneFile(path);
                    }
                }
                return; // 任务完成，退出递归
            } else if (state == 7) {
                // 失败状态
                updateCopyStatus(copy, "2");
                TgHelper.sendMsg("<b>复制任务失败</b>\n" +
                        "源目录：" + StringUtils.escapeHtml(copy.getCopySrcPath()) + "\n" +
                        "源文件名：" + StringUtils.escapeHtml(copy.getCopySrcFileName()));
                return; // 任务失败，退出递归
            }

            if (Instant.now().isAfter(deadline)) {
                // 超过最长监控时长仍未结束：强制标记为异常，停止继续调度
                updateCopyStatus(copy, "4");
                Duration duration = monitorDuration();
                log.warn("单文件复制监控超时（超过 {}），已标记为异常并停止监控: taskId={}, path={}",
                        duration, copy.getCopyTaskId(), path);
                TgHelper.sendMsg("<b>复制任务监控超时</b>\n" +
                        "文件：" + StringUtils.escapeHtml(path) + "\n" +
                        "超过 " + duration.toMinutes() + " 分钟未结束，已停止监控，请人工核查");
                return;
            }

            // 任务仍在运行中，按退避间隔继续调度下一次检查
            long interval = nextIntervalSeconds(round);
            scheduler.schedule(Threads.wrap(() -> checkOneFileRecursive(path, copy, deadline, round + 1)),
                    Instant.now().plusSeconds(interval));

        } catch (Exception e) {
            log.error("Error in checkOneFileRecursive for path: {}", path, e);
        }
    }

    // 辅助方法：复制任务状态监控的最长持续时间（可通过 sys_config 配置）
    private Duration monitorDuration() {
        return Duration.ofMinutes(config.getCopyMonitorMaxMinutes());
    }

    // 辅助方法：更新数据库状态
    private void updateCopyStatus(OpenlistCopyPlus copy, String status) {
        copy.setCopyStatus(status);
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
            log.error("Error generating strm for dir: {}", dstDir, e);
        }
    }
}