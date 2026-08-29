package com.osr.openliststrm.mcp;

import com.osr.common.utils.Threads;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 长耗时工具的异步作业登记处。
 * <p>
 * <b>为什么需要它</b>：补搜一集的墙钟上限是「季搜索 30 秒 + 单集补发 180 秒」
 * （见根目录 AGENTS.md 关于 {@code indexer-budget-ms} 与 {@code per-episode-fallback-limit}
 * 的条目，网页端的超时就是按 240 秒配的），而 MCP 客户端普遍是 60 秒默认超时。
 * 同步等的结果是：模型那边超时报错，服务端这边照样在跑，模型多半会<b>再调一次</b>——
 * 于是同一条订阅被并发补搜两遍，把索引器请求量直接翻倍。
 * </p>
 * <p>
 * <b>刻意不用 MCP 的 progress notification</b>：那要求客户端实现 {@code progressToken}，
 * 各家参差；而"返回一个 id、隔一会儿再查一次"是任何模型都会的模式，代价只是多一个工具。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class McpJobService {

    /**
     * 同时最多跑几个作业。
     * <p>
     * 这个数字压得很低是有理由的：作业几乎都会打索引器，而索引器侧已经有全局的
     * {@code IndexerRateLimiter}——多开只会在限流器上排队，并把等待推过
     * {@code pt.indexer.max-wait-ms} 触发<b>静默跳过</b>（症状是"搜索结果凭空少一批"）。
     * 这与 {@code AutoSearchService} 刻意串行、不用并发解决问题是同一条理由。
     * </p>
     */
    private static final int MAX_CONCURRENT_JOBS = 2;

    /** 排队上限。满了直接拒绝并说清楚，比让模型等一个永远轮不到的作业强 */
    private static final int MAX_QUEUED_JOBS = 8;

    /** 作业记录的保留上限与保留时长，两者取先到的那个 */
    private static final int MAX_RETAINED_JOBS = 100;
    private static final Duration RETENTION = Duration.ofHours(2);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private final ExecutorService executor = new ThreadPoolExecutor(
            MAX_CONCURRENT_JOBS, MAX_CONCURRENT_JOBS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_JOBS),
            runnable -> {
                Thread thread = new Thread(runnable, "mcp-job");
                thread.setDaemon(true);
                return thread;
            });

    /** 作业状态 */
    public enum JobState {
        /** 排队或执行中 */
        RUNNING,
        /** 正常结束 */
        SUCCEEDED,
        /** 执行中抛了异常 */
        FAILED
    }

    private static final class Job {
        private final String id;
        private final String tool;
        private final Long ownerUserId;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private volatile JobState state = JobState.RUNNING;
        private volatile LocalDateTime finishedAt;
        private volatile Object result;
        private volatile String error;

        private Job(String id, String tool, Long ownerUserId) {
            this.id = id;
            this.tool = tool;
            this.ownerUserId = ownerUserId;
        }
    }

    /**
     * 提交一个作业。
     *
     * @param tool      工具名，仅用于展示与日志
     * @param principal 发起人；作业内部会以这个身份执行，查询结果时也按它做归属校验
     * @param work      实际工作。<b>它跑在别的线程上</b>，因此不要在里面依赖调用线程的任何上下文
     * @return 作业 id
     */
    public String submit(String tool, McpPrincipal principal, Supplier<Object> work) {
        purgeStale();
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        Job job = new Job(jobId, tool, principal.user() != null ? principal.user().getUserId() : null);
        jobs.put(jobId, job);
        try {
            // Threads.wrap 负责把 MDC（traceId）带过线程边界——不包的话这个作业产生的日志
            // 与触发它的那次调用在实时日志页里串不成一条链路，而作业恰恰是最需要事后追查的部分
            executor.execute(Threads.wrap(() -> run(job, principal, work)));
        } catch (RejectedExecutionException e) {
            jobs.remove(jobId);
            throw new McpToolException("后台作业队列已满（同时最多 " + MAX_CONCURRENT_JOBS + " 个在跑、"
                    + MAX_QUEUED_JOBS + " 个排队），请等已有作业结束后再试");
        }
        return jobId;
    }

    private void run(Job job, McpPrincipal principal, Supplier<Object> work) {
        // 作业线程上没有任何身份，必须自己绑一次——否则它以匿名身份执行，
        // 表现是"补搜跑完了，但一条订阅都没找到"
        try (McpCallContext.Binding ignored = McpCallContext.bind(principal)) {
            job.result = work.get();
            job.state = JobState.SUCCEEDED;
        } catch (Exception e) {
            job.state = JobState.FAILED;
            job.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("MCP 后台作业 {}({}) 失败：{}", job.tool, job.id, e.getMessage(), e);
        } finally {
            job.finishedAt = LocalDateTime.now();
        }
    }

    /**
     * 查作业状态。
     *
     * @throws McpToolException 作业不存在（含已过期被清理），或不属于查询者
     */
    public Map<String, Object> status(String jobId, McpPrincipal principal) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw new McpToolException("作业 " + jobId + " 不存在或已过期（作业记录保留 "
                    + RETENTION.toHours() + " 小时）");
        }
        Long requester = principal.user() != null ? principal.user().getUserId() : null;
        if (job.ownerUserId != null && !job.ownerUserId.equals(requester)) {
            // 与订阅那侧一样，不区分"不存在"和"不是你的"——区分开就等于给了一个
            // 逐个 id 试探、看别人在做什么的接口
            throw new McpToolException("作业 " + jobId + " 不存在或已过期（作业记录保留 "
                    + RETENTION.toHours() + " 小时）");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", job.id);
        view.put("tool", job.tool);
        view.put("state", job.state.name());
        view.put("startedAt", job.startedAt.format(TIME_FORMAT));
        view.put("finishedAt", job.finishedAt == null ? null : job.finishedAt.format(TIME_FORMAT));
        view.put("result", job.state == JobState.SUCCEEDED ? job.result : null);
        view.put("error", job.error);
        if (job.state == JobState.RUNNING) {
            view.put("hint", "作业仍在执行，请等待若干秒后再次调用 get_job_status 查询同一个 jobId");
        }
        return view;
    }

    /** 清掉过期与超量的<b>已结束</b>作业。执行中的作业永远不清，否则它一结束就查不到结果了 */
    private void purgeStale() {
        LocalDateTime deadline = LocalDateTime.now().minus(RETENTION);
        jobs.values().removeIf(job -> job.finishedAt != null && job.finishedAt.isBefore(deadline));
        if (jobs.size() <= MAX_RETAINED_JOBS) {
            return;
        }
        List<Job> finished = jobs.values().stream()
                .filter(job -> job.finishedAt != null)
                .sorted(Comparator.comparing(job -> job.finishedAt))
                .toList();
        int excess = jobs.size() - MAX_RETAINED_JOBS;
        for (int i = 0; i < excess && i < finished.size(); i++) {
            jobs.remove(finished.get(i).id);
        }
    }

    @PreDestroy
    public void shutdown() {
        Threads.shutdownAndAwaitTermination(executor);
    }
}
