package com.osr.openliststrm.pt.calendar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把 TMDb 的每集播出日期同步到 pt_subscription_episode.air_date，追剧日历的数据来源。
 * <p>
 * <b>为什么要定期跑而不是一次性回填</b>：播出日期本来就会变——改档、提前放送、季中休播，
 * 一次性脚本解决不了。定期同步顺带承担了存量数据的回填，因此升级上来的库不需要单独的迁移动作。
 * </p>
 * <p>
 * 只同步剧集：电影的上映日期在 TMDb 的详情里而不是季端点，且电影订阅只有一条 episode=0 的记录，
 * 排进日历的价值远不如剧集。留待有需要时再补。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class EpisodeAirDateSyncService {

    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final TmdbSearchService tmdbSearchService;

    public EpisodeAirDateSyncService(IPtSubscriptionPlusService subscriptionService,
                                     IPtSubscriptionEpisodePlusService episodeService,
                                     TmdbSearchService tmdbSearchService) {
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.tmdbSearchService = tmdbSearchService;
    }

    /**
     * 同步剧集订阅的播出日期。
     * <p>
     * 取两类订阅：<b>活跃的</b>（日期还会变，每轮都要跟）与<b>还有集没日期的</b>
     * （无论什么状态，属于存量回填）。已完结且日期齐了的订阅不再重复拉——它们的播出日期
     * 不会再变，每 12 小时给它们打一次 TMDb 是纯浪费；但完结订阅也得能进日历，
     * 所以不能简单地按 status 过滤掉，否则升级前就已完结的剧永远补不上日期。
     * </p>
     *
     * @return 实际更新的集行数
     */
    public int syncAll() {
        List<PtSubscriptionPlus> tvSubs = subscriptionService.list(new LambdaQueryWrapper<PtSubscriptionPlus>()
                .ne(PtSubscriptionPlus::getMediaType, SubscriptionService.TYPE_MOVIE));
        Set<Integer> needBackfill = subIdsMissingAirDate();

        List<PtSubscriptionPlus> targets = tvSubs.stream()
                .filter(sub -> SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())
                        || needBackfill.contains(sub.getId()))
                .toList();

        int updated = 0;
        for (PtSubscriptionPlus sub : targets) {
            try {
                updated += syncOne(sub);
            } catch (Exception e) {
                // 单个订阅取不到日期（TMDb 改过季结构、ID 失效…）不该让整轮同步中断
                log.warn("同步订阅[{}] {} 的播出日期失败：{}", sub.getId(), sub.getTitle(), e.getMessage());
            }
        }
        if (updated > 0) {
            // 报实际扫到的回填数而不是 needBackfill.size()：后者按集行统计，
            // 把不参与同步的电影订阅也算了进去，日志里会出现「待回填数 > 扫描数」这种读不通的组合
            long backfilled = targets.stream().filter(sub -> needBackfill.contains(sub.getId())).count();
            log.info("播出日期同步完成，共更新 {} 条集记录（扫描剧集订阅 {} 个，其中回填 {} 个）",
                    updated, targets.size(), backfilled);
        }
        return updated;
    }

    /** 还有集没拿到播出日期或 TMDb 集号的订阅，这些无论状态都要拉一次 */
    private Set<Integer> subIdsMissingAirDate() {
        return episodeService.list(new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                        .select(PtSubscriptionEpisodePlus::getSubId)
                        .and(w -> w.isNull(PtSubscriptionEpisodePlus::getAirDate)
                                .or().isNull(PtSubscriptionEpisodePlus::getTmdbEpisodeNumber)))
                .stream()
                .map(PtSubscriptionEpisodePlus::getSubId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 同步单个订阅。只写「日期确实变了」的行——整季无条件 update 会把 update_time 全刷一遍，
     * 让人误以为每 12 小时都有实际变化。
     *
     * @return 实际更新的集行数
     */
    public int syncOne(PtSubscriptionPlus sub) {
        if (sub.getTmdbId() == null || sub.getSeason() == null) {
            return 0;
        }
        Map<Integer, LocalDate> airDates = tmdbSearchService.getSeasonEpisodeAirDates(sub.getTmdbId(), sub.getSeason());
        if (airDates.isEmpty()) {
            return 0;
        }
        List<PtSubscriptionEpisodePlus> episodes = episodeService.list(
                new LambdaQueryWrapper<PtSubscriptionEpisodePlus>().eq(PtSubscriptionEpisodePlus::getSubId, sub.getId()));

        // TMDb 的集号未必与本地同一套（长篇动画用绝对集号），交给 TmdbEpisodeAligner 对齐
        Map<Integer, TmdbEpisodeAligner.TmdbEpisodeRef> aligned = TmdbEpisodeAligner.align(
                episodes.stream().map(PtSubscriptionEpisodePlus::getEpisode).filter(Objects::nonNull).toList(),
                airDates);

        List<PtSubscriptionEpisodePlus> changed = new ArrayList<>();
        for (PtSubscriptionEpisodePlus episode : episodes) {
            TmdbEpisodeAligner.TmdbEpisodeRef ref = aligned.get(episode.getEpisode());
            if (ref == null) {
                // TMDb 撤掉了日期（改回未定档）时不清空已有值：撤档信息本身不可靠，
                // 清掉会让这集从日历上凭空消失，比留着一个旧日期更让人困惑
                continue;
            }
            boolean dateChanged = ref.airDate() != null
                    && !Objects.equals(toLocalDate(episode.getAirDate()), ref.airDate());
            boolean numberChanged = !Objects.equals(episode.getTmdbEpisodeNumber(), ref.episodeNumber());
            if (!dateChanged && !numberChanged) {
                continue;
            }
            PtSubscriptionEpisodePlus patch = new PtSubscriptionEpisodePlus();
            patch.setId(episode.getId());
            if (dateChanged) {
                patch.setAirDate(Date.from(ref.airDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
            }
            patch.setTmdbEpisodeNumber(ref.episodeNumber());
            changed.add(patch);
        }
        if (changed.isEmpty()) {
            return 0;
        }
        episodeService.updateBatchById(changed);
        return changed.size();
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
