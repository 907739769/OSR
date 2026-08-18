package com.osr.openliststrm.pt.calendar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.calendar.dto.CalendarEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 追剧日历查询。数据来自 pt_subscription_episode.air_date（由 {@link EpisodeAirDateSyncService} 维护），
 * 因此这里是一次纯 SQL 范围查询，不打 TMDb。
 *
 * @author Jack
 */
@Slf4j
@Service
public class PtCalendarService {

    /**
     * 单次查询允许的最大跨度。日历视图最多按月/周翻，一年足够；
     * 没有上限的话一个手搓的 start=1970 请求就能把整张表拉进内存。
     */
    static final int MAX_RANGE_DAYS = 366;

    private final IPtSubscriptionEpisodePlusService episodeService;
    private final IPtSubscriptionPlusService subscriptionService;

    public PtCalendarService(IPtSubscriptionEpisodePlusService episodeService,
                             IPtSubscriptionPlusService subscriptionService) {
        this.episodeService = episodeService;
        this.subscriptionService = subscriptionService;
    }

    /** 暂停的订阅不进日历：用户已经明确表态不再追它，占着格子只是噪音（口径同缺集体检） */
    private static final String STATUS_PAUSED = "PAUSED";

    /**
     * 查询区间内有播出日期的集，按「日期 → 剧名 → 集号」排序。
     *
     * @param start      起始日期（含）
     * @param end        结束日期（含）
     * @param accessible 归属过滤：只保留当前用户能看到的订阅。放在这里而不是 SQL 里，
     *                   是因为「谁能看什么」的判据在 Controller 层（管理员看全部、
     *                   其余人看自己的与无归属的），服务层不该知道当前登录用户是谁——
     *                   与 {@code EpisodeHealthService#report} 同一个姿势。
     *                   <p>
     *                   <b>这层过滤不能省。</b>日历是 pt_subscription 的第三个消费者，
     *                   另外两个（{@code PtSubscriptionRestController#buildQueryWrapper}、
     *                   {@code PtHealthRestController#report}）都做了归属判定；漏掉的话
     *                   非管理员会在日历格子里看到全站所有人订阅的剧名、海报与季集号，
     *                   而点进去时订阅页才拦下——内容早就露出去了。
     *                   </p>
     * @throws IllegalArgumentException 区间非法或跨度超过 {@link #MAX_RANGE_DAYS}
     */
    public List<CalendarEntry> query(LocalDate start, LocalDate end,
                                     Predicate<PtSubscriptionPlus> accessible) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("起止日期不能为空");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new IllegalArgumentException("查询跨度不能超过 " + MAX_RANGE_DAYS + " 天");
        }

        // 结束日当天也要含进来：air_date 是 date 类型，但字段映射成 java.util.Date 后
        // 比较走的是 datetime 语义，用 end 的 00:00 做上界会把当天整天漏掉
        List<PtSubscriptionEpisodePlus> episodes = episodeService.list(
                new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                        .isNotNull(PtSubscriptionEpisodePlus::getAirDate)
                        .ge(PtSubscriptionEpisodePlus::getAirDate, toDate(start))
                        .lt(PtSubscriptionEpisodePlus::getAirDate, toDate(end.plusDays(1))));
        if (episodes.isEmpty()) {
            return List.of();
        }

        // 订阅表很小，按用到的 id 批量捞一次内存拼装，省掉一份 XML 关联查询
        Set<Integer> subIds = episodes.stream()
                .map(PtSubscriptionEpisodePlus::getSubId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Integer, PtSubscriptionPlus> subs = subscriptionService.listByIds(subIds).stream()
                .collect(Collectors.toMap(PtSubscriptionPlus::getId, Function.identity(), (a, b) -> a));

        return episodes.stream()
                // 订阅已被删除但集行还在时跳过：拼不出剧名的格子对用户没有意义
                .filter(e -> subs.containsKey(e.getSubId()))
                // 归属过滤与暂停过滤都在这里做：上面那次 listByIds 是按集表出现过的 id 捞的，
                // 到这一步才有订阅实体可判
                .filter(e -> accessible.test(subs.get(e.getSubId())))
                .filter(e -> !STATUS_PAUSED.equals(subs.get(e.getSubId()).getStatus()))
                .map(e -> {
                    PtSubscriptionPlus sub = subs.get(e.getSubId());
                    return new CalendarEntry(
                            toLocalDate(e.getAirDate()).toString(),
                            sub.getId(), sub.getTmdbId(), sub.getTitle(), sub.getPosterPath(),
                            sub.getSeason(), e.getEpisode(), e.getState());
                })
                .sorted(Comparator.comparing(CalendarEntry::airDate)
                        .thenComparing(c -> c.title() == null ? "" : c.title())
                        .thenComparing(c -> c.episode() == null ? 0 : c.episode()))
                .toList();
    }

    private static Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
