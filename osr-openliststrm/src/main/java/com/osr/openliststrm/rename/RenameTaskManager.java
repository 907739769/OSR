package com.osr.openliststrm.rename;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.monitor.processor.MediaRenameProcessor;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理重命名任务的类。
 * 轮询重命名任务表并保持FileMonitorService实例运行的类。
 */
@Slf4j
@Component
public class RenameTaskManager {


    @Autowired
    private OpenlistConfig config;
    @Autowired
    private OpenListHelper helper;
    @Autowired
    private RenameClientProvider clientProvider;
    @Autowired
    private RenameEventListenerFactory listenerFactory;
    @Autowired
    private IRenameTaskPlusService renameTaskService;
    @Autowired
    private IRenameDetailPlusService renameDetailService;

    private final RenameMonitorRegistry registry = new RenameMonitorRegistry();
    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(10), Duration.ofSeconds(10));
        log.info("RenameTaskManager started");
    }

    @PreDestroy
    public void stop() {
        registry.stopAll();
        log.info("RenameTaskManager stopped");
        MDC.clear();
    }

    public void executeRenameDetails(Integer id, String title, String year, String season, String episode) {
        RenameDetailPlus rd = renameDetailService.getById(id);
        if (rd == null) {
            log.warn("重命名任务不存在：{}", id);
            return;
        }

        String tgt = rd.getNewPath();
        Path path = Paths.get(tgt);
        Path target = path;
        for (int i = path.getNameCount() - 1; i >= 0; i--) {
            String name = path.getName(i).toString();
            if (name.equals("电影") || name.equals("电视剧")) {
                target = path.getRoot().resolve(path.subpath(0, i));
                break;
            }
        }
        tgt = target.toString();

        // 用户留空则传空字符串，由 MediaParser 从文件名重新识别；手动填写则覆盖
        MediaRenameProcessor processor = new MediaRenameProcessor(
                Paths.get(tgt),
                clientProvider,
                helper,
                config,
                listenerFactory.create(id)
        );
        processor.handleOneFile(Paths.get(rd.getOriginalPath()).resolve(rd.getOriginalName()),
                StringUtils.isNotBlank(title) ? title : null,
                StringUtils.isNotBlank(year) ? year : null,
                season, episode);
    }

    /**
     * 立即执行单个任务一次（用于页面手动触发）。
     * 返回 true 表示已成功触发处理（不代表全部文件处理成功）。
     */
    public void executeTaskNow(Integer taskId) {
        try {
            if (taskId == null) return;
            RenameTaskPlus task = renameTaskService.getById(taskId);
            if (task == null) {
                log.warn("立即执行：重命名任务 {} 不存在", taskId);
                return;
            }
            String src = task.getSourceFolder();
            String tgt = task.getTargetRoot();
            if (src == null || tgt == null) {
                log.warn("立即执行：重命名任务 {} 缺少源目录或目标目录", taskId);
                return;
            }
            MediaRenameProcessor processor = new MediaRenameProcessor(
                    Paths.get(tgt),
                    clientProvider,
                    helper,
                    config,
                    listenerFactory.create(taskId)
            );
            processor.processOnce(Paths.get(src));
        } catch (Exception e) {
            log.error("立即执行重命名任务 {} 失败：{}", taskId, e.getMessage(), e);
        }
    }

    private void poll() {
        try {
            //更新配置
            clientProvider.refresh(config);
            if (!clientProvider.available()) {
                registry.stopAll();
                return;
            }
            //查询所有启用的重命名任务
            Map<Integer, RenameTaskPlus> activeTasks = loadActiveTasks();
            registry.reconcile(activeTasks, clientProvider, helper, config, listenerFactory);
        } catch (Exception e) {
            log.error("poll error", e);
        }
    }


    private Map<Integer, RenameTaskPlus> loadActiveTasks() {
        QueryWrapper<RenameTaskPlus> qw = new QueryWrapper<>();
        qw.eq("status", "1");
        List<RenameTaskPlus> list = renameTaskService.list(qw);
        return list.stream().collect(Collectors.toMap(RenameTaskPlus::getId, t -> t));
    }

    /**
     * 超过这个条数就转异步执行，与 {@code StrmServiceImpl#retryStrm} / {@code CopyServiceImpl#retryCopy}
     * 的阈值保持一致。小批量留在调用线程上，好让 TG 的回复消息反映的是真实完成情况。
     */
    private static final int ASYNC_THRESHOLD = 20;

    /**
     * 批量重试所有失败的重命名记录（最多重试最新 200 条）。
     * <p>
     * 超过 {@link #ASYNC_THRESHOLD} 条时转异步：唯一的调用方是 Telegram 的 {@code /retry} 指令
     * （{@code StrmBot#retry}），而它串行调三个 retryAllFailed 之后才发那条汇总回复。本方法原先
     * 是同步的——200 条 {@code executeRenameDetails} 各要查 TMDb、复制文件、刮削，全部串在
     * Telegram 的 Ability action 里跑完，用户看到「开始重试所有失败任务」之后要静默等几十分钟
     * 才能等到结果。STRM 与同步那两条早就是异步的，只有这里漏了。
     * <p>
     * <b>刻意不并发化</b>，只是挪出调用线程：executeRenameDetails 会查 TMDb（有配额）、
     * 写刮削文件（{@code ScrapeService} 的兄弟计数依赖 rename_detail 的当前状态），并发跑
     * 是另一个量级的改动，不该混在"别阻塞 TG"这件事里。
     * <p>
     * 异步执行本身是安全的：{@code RenameDetailRestController} 早就在用
     * {@code AsyncManager.me().execute(() -> renameTaskManager.executeRenameDetails(...))}，
     * 这条路径不是新引入的线程模型。
     * <p>
     * executeRenameDetails 只在内部处理了 IOException，其他运行时异常会往外抛，
     * 所以必须对每条记录单独 try/catch，避免一条异常中断整批。
     */
    public RetryOutcome retryAllFailed() {
        QueryWrapper<RenameDetailPlus> countWrapper = new QueryWrapper<>();
        countWrapper.eq("status", "0");
        long total = renameDetailService.count(countWrapper);
        if (total == 0) {
            return new RetryOutcome(0, 0);
        }

        QueryWrapper<RenameDetailPlus> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "0")
                .select("id")
                .orderByDesc("create_time")
                .last("LIMIT 200");
        List<RenameDetailPlus> failed = renameDetailService.list(wrapper);
        List<Integer> ids = failed.stream().map(RenameDetailPlus::getId).toList();

        Runnable action = () -> {
            for (Integer id : ids) {
                try {
                    executeRenameDetails(id, null, null, null, null);
                } catch (Exception e) {
                    log.warn("retryAllFailed: 重试重命名明细失败 id={}", id, e);
                }
            }
        };
        if (ids.size() > ASYNC_THRESHOLD) {
            submitAsync(action);
        } else {
            action.run();
        }
        return new RetryOutcome(ids.size(), (int) total - ids.size());
    }

    /**
     * 提交异步执行。抽成包级可见的方法是为了让单测能验证「超过阈值不在调用线程上跑」这条分支——
     * {@code AsyncManager} 是静态单例，它的静态初始化就会调 {@code SpringUtils.getBean}，
     * 在纯单测里 mockStatic 它的成败取决于该类此前有没有被别的用例加载过，判据不稳。
     */
    void submitAsync(Runnable action) {
        AsyncManager.me().execute(action);
    }

    /**
     * @param retried   本次<b>提交</b>重试的记录数（超过 {@link #ASYNC_THRESHOLD} 时为已提交、尚未完成）
     * @param remaining 超出 200 条上限、本次完全没被捞起来的剩余失败记录数
     */
    public record RetryOutcome(int retried, int remaining) {}
}