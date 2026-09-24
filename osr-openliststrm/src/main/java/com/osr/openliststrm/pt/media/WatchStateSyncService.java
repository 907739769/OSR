package com.osr.openliststrm.pt.media;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.FaultThrottle;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.PtLogText;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把媒体库里的观看状态同步到订阅上（{@code watched_count} / {@code last_watched_time}）。
 * <p>
 * 多台媒体服务器时取<b>并集</b>：看过的集合并、最近观看时间取最晚——与入库判定「任一台命中即算」同一条取向。
 * 读不到观看状态的服务器（不支持、Emby 没配用户 ID）<b>不参与</b>，一台都读不到时这条订阅原值不动，
 * 不能拿「读不到」去覆盖成「一集都没看」。
 *
 * @author Jack
 */
@Slf4j
@Service
public class WatchStateSyncService {

    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtMediaServerPlusService mediaServerService;
    private final MediaServerClientFactory clientFactory;

    /** 按服务器节流失败日志：服务器宕机时每条订阅各失败一次，不节流就是一轮几十条逐字相同的 WARN */
    private final FaultThrottle faultThrottle = new FaultThrottle();

    public WatchStateSyncService(IPtSubscriptionPlusService subscriptionService,
                                 IPtMediaServerPlusService mediaServerService,
                                 MediaServerClientFactory clientFactory) {
        this.subscriptionService = subscriptionService;
        this.mediaServerService = mediaServerService;
        this.clientFactory = clientFactory;
    }

    /**
     * @return 观看状态有变化、写了库的订阅数
     */
    public int syncAll() {
        List<PtMediaServerPlus> servers = mediaServerService.listActive();
        if (servers.isEmpty()) {
            return 0;
        }
        List<PtSubscriptionPlus> subs = subscriptionService.list(new LambdaQueryWrapper<PtSubscriptionPlus>()
                .in(PtSubscriptionPlus::getStatus, SubscriptionService.STATUS_ACTIVE, SubscriptionService.STATUS_COMPLETED));
        int changed = 0;
        for (PtSubscriptionPlus sub : subs) {
            WatchState state = readMerged(servers, sub);
            if (state == null) {
                continue;
            }
            Integer count = state.watchedEpisodes().size();
            Date last = truncateToSeconds(state.lastWatched());
            if (Objects.equals(count, sub.getWatchedCount())
                    && Objects.equals(last, truncateToSeconds(sub.getLastWatchedTime()))) {
                continue;
            }
            subscriptionService.updateWatchState(sub.getId(), count, last);
            changed++;
            log.debug("{} 观看状态：已看 {} 集，最近观看 {}", PtLogText.subject(sub), count, last);
        }
        return changed;
    }

    /** 多台服务器取并集；一台都读不到返回 null */
    WatchState readMerged(List<PtMediaServerPlus> servers, PtSubscriptionPlus sub) {
        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
        Set<Integer> watched = null;
        Date last = null;
        for (PtMediaServerPlus server : servers) {
            String key = "watch:" + server.getId();
            WatchState state;
            try {
                state = clientFactory.get(server).watchState(server, sub.getTmdbId(), movie ? null : sub.getSeason(), movie);
                if (faultThrottle.onSuccess(key)) {
                    log.info("媒体服务器[{}]读取观看状态已恢复", server.getName());
                }
            } catch (Exception e) {
                FaultThrottle.Decision d = faultThrottle.onFailure(key);
                if (d.shouldReport()) {
                    log.warn("从媒体服务器[{}]读取观看状态失败（连续第 {} 次），本轮这台不参与：{}",
                            server.getName(), d.consecutiveFailures(), e.getMessage());
                }
                continue;
            }
            if (state == null) {
                continue;
            }
            if (watched == null) {
                watched = new HashSet<>();
            }
            watched.addAll(state.watchedEpisodes());
            if (state.lastWatched() != null && (last == null || state.lastWatched().after(last))) {
                last = state.lastWatched();
            }
        }
        return watched == null ? null : new WatchState(watched, last);
    }

    /** 库里的 DATETIME 只到秒，不截断的话带毫秒的新值与库里的旧值永远「不相等」，每小时白写一遍 */
    private static Date truncateToSeconds(Date date) {
        return date == null ? null : new Date(date.getTime() / 1000 * 1000);
    }
}
