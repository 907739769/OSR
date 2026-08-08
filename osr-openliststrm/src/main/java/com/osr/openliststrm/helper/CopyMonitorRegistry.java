package com.osr.openliststrm.helper;

import com.osr.common.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「哪些复制任务此刻有内存监控链在盯着」的心跳表。
 *
 * <p>{@link AsynHelper} 的监控是内存里的递归自调度，{@link CopyRecoveryTask} 是读库的兜底扫描，
 * 两者都会给同一条记录下终态判定。心跳表就是它们之间的分工凭据：内存链每轮给自己还在盯的
 * taskId 续一次心跳，兜底扫描跳过心跳未过期的记录，只捡没人认领的那些。
 *
 * <p>心跳有效期取 5 分钟：内存链的最大退避间隔是 60 秒（见 {@code AsynHelper#nextIntervalSeconds}），
 * 留 5 倍冗余，即使某一轮因为网络慢拖长了也不会被误判成「没人管」。
 *
 * <p>这张表<b>只存在于进程内</b>——这正是它想表达的语义：进程一重启，所有心跳消失，
 * 库里还是「处理中」的记录就全部变成无主记录，等着兜底扫描来接管。
 */
@Slf4j
@Component
public class CopyMonitorRegistry {

    /** 心跳有效期，超过这个时长没续约就视为内存监控已消失 */
    private static final Duration HEARTBEAT_TTL = Duration.ofMinutes(5);

    private final Map<String, Instant> heartbeats = new ConcurrentHashMap<>();

    /** 续约单个任务的心跳 */
    public void heartbeat(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            return;
        }
        heartbeats.put(taskId, Instant.now());
    }

    /** 续约一批任务的心跳 */
    public void heartbeat(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (String taskId : taskIds) {
            if (StringUtils.isNotBlank(taskId)) {
                heartbeats.put(taskId, now);
            }
        }
    }

    /** 该任务当前是否有内存监控链在盯着（心跳未过期） */
    public boolean monitoredInMemory(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            return false;
        }
        Instant last = heartbeats.get(taskId);
        if (last == null) {
            return false;
        }
        if (Instant.now().isAfter(last.plus(HEARTBEAT_TTL))) {
            heartbeats.remove(taskId, last);
            return false;
        }
        return true;
    }

    /**
     * 清掉过期心跳。内存链走到终态时不会主动摘牌（那些记录已经不是「处理中」，
     * 兜底扫描根本查不到它们，摘不摘牌都不影响判定），所以靠这里做周期性回收，
     * 否则条目只增不减。由 {@link CopyRecoveryTask} 每轮调用。
     *
     * @return 本次清掉的条目数
     */
    public int evictExpired() {
        Instant deadline = Instant.now().minus(HEARTBEAT_TTL);
        int before = heartbeats.size();
        heartbeats.entrySet().removeIf(e -> e.getValue().isBefore(deadline));
        return before - heartbeats.size();
    }

    /** 当前心跳条目数，仅用于日志 */
    public int size() {
        return heartbeats.size();
    }
}
