package com.osr.openliststrm.pt.media;

import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体服务器连通状态的<b>被动</b>记录：业务每次真正用到某台服务器时把结果写回，
 * 页面据此显示「上次连通 / 上次失败 + 原因」。
 *
 * <p><b>为什么被动而不是起个周期任务去打 /System/Info</b>：探针通了不代表查得到数据
 * （反代只转发了一部分路径、API Key 权限不足、库没挂上，都是探针 200 而业务查询空手而归），
 * 而这里要回答的恰恰是「对账用它的时候好不好使」。被动记录零额外请求，记下的就是这个答案。
 * 代价是库里一条 ACTIVE 订阅都没有时状态不刷新，页面会显示一个较旧的时间——可以接受，
 * 那种情况下媒体服务器通不通本来也不影响任何事。
 *
 * <p><b>落库必须节流</b>。对账每 10 分钟跑一遍全部订阅，而 {@code queryLibrary} 是<b>每条订阅</b>
 * 调一次的：按实测库里 103 条订阅算，不节流就是每 10 分钟 103 次 UPDATE，把一张几乎不变的配置表
 * 刷成热点。规则是「状态变了立刻写，没变则按 {@link #PERSIST_INTERVAL_MILLIS} 刷一次时间」——
 * 与 {@code EpisodeAirDateSyncTask} 只写日期确实变了的行是同一条取向，也保证了
 * 「刚刚坏掉」「刚刚恢复」这两个唯一有信息量的时刻一定落得进库。
 *
 * <p><b>本类用实例字段存跨轮次状态是正确的</b>，不违反「{@code @Component} 是单例、不能用实例字段
 * 存状态」那条规矩：那条针对的是<b>单次请求</b>的状态（并发请求会互相覆盖，{@code ApiInterceptor}
 * 的耗时就是这么错的），而这里存的是需要跨轮次存活的<b>组件级</b>状态——放进局部变量的话每轮重新
 * 开始，节流就不存在了。判据同 {@link com.osr.common.utils.FaultThrottle}。
 *
 * @author Jack
 */
@Slf4j
@Component
public class MediaServerHealthRecorder {

    /** 状态没变化时的落库间隔。取 10 分钟与对账周期同量级：页面上那个时间最多旧十几分钟 */
    static final long PERSIST_INTERVAL_MILLIS = 10 * 60_000L;

    private final IPtMediaServerPlusService mediaServerService;

    /**
     * serverId → 上次<b>已落库</b>的那次结果。进程内内存态，重启后至多多写一次库，不值得为它持久化。
     * 条目数等于媒体服务器台数（个位数），不会无界增长。
     */
    private final Map<Integer, Persisted> persisted = new ConcurrentHashMap<>();

    public MediaServerHealthRecorder(IPtMediaServerPlusService mediaServerService) {
        this.mediaServerService = mediaServerService;
    }

    /** 记一次成功访问。 */
    public void recordSuccess(Integer serverId) {
        record(serverId, true, null);
    }

    /** 记一次失败访问，{@code error} 会展示在配置页上，要写得能指导处置。 */
    public void recordFailure(Integer serverId, String error) {
        record(serverId, false, error);
    }

    private void record(Integer serverId, boolean ok, String error) {
        record(serverId, ok, error, System.currentTimeMillis());
    }

    /**
     * 当前时刻做成参数，供测试钉住节流的时间算术。
     * <p>
     * <b>刻意不加一个注入 {@code Clock} 的第二构造器</b>：一个 bean 有多个构造器时 Spring 不会自己挑，
     * 没标 {@code @Autowired} 就退回去找默认构造器、找不到就整个应用装配失败，而单测直接 new、
     * 绕开 Spring、全绿——{@code LoginAttemptService} 正是这么让后端崩溃重启了 6 次。
     * 需要可控时钟时优先改成传参，同 {@code EpisodeHealthService#scan(LocalDate)}。
     * </p>
     */
    void record(Integer serverId, boolean ok, String error, long now) {
        if (serverId == null) {
            return;
        }
        // compute 的重映射函数只做决策、不碰数据库：它在 ConcurrentHashMap 的 bin 锁内执行，
        // 官方文档明确禁止在里面做耗时操作（IndexerCapabilityCache 踩过同一个坑）
        boolean[] shouldPersist = {false};
        persisted.compute(serverId, (id, prev) -> {
            if (prev == null || prev.ok != ok || !Objects.equals(prev.error, error)
                    || now - prev.at >= PERSIST_INTERVAL_MILLIS) {
                shouldPersist[0] = true;
                return new Persisted(ok, error, now);
            }
            return prev;
        });
        if (!shouldPersist[0]) {
            return;
        }
        try {
            mediaServerService.updateProbeResult(serverId, ok, error);
        } catch (Exception e) {
            // 落库失败就把记忆丢掉，让下一次访问重新判定为「需要落库」而不是等满一个间隔。
            // 连通状态只是个展示信号，写不进去绝不能让对账本身出错——所以这里吞掉异常
            persisted.remove(serverId);
            log.debug("写回媒体服务器[{}]连通状态失败：{}", serverId, e.getMessage());
        }
    }

    /** 上次已落库的那次结果 */
    private record Persisted(boolean ok, String error, long at) {
    }
}
