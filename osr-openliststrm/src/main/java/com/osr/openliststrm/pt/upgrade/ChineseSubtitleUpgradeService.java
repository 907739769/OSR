package com.osr.openliststrm.pt.upgrade;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.PtLogText;
import com.osr.openliststrm.pt.filter.EpisodeCountResolver;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.FilterCriteriaFactory;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.PushOutcome;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 字幕体检之后的补救：给媒体服务器上没有中文字幕的集，从 PT 站重新下一个<b>带中字的版本</b>。
 * <p>
 * 为什么不是「补一份外挂字幕」：国内字幕源基本都停了，Emby/Jellyfin 的字幕插件搜不出东西；而 PT 站上
 * 同一集几乎总有带中字的发布（内封简繁、简英双语）。代价是要再下一份视频——所以这是用户当面点的按钮，
 * 不是自动任务。
 * </p>
 * <p>
 * 推送走洗版通道（{@link SubscriptionEngine#pushUpgradeOutcome}）：这一集 IN_LIBRARY → UPGRADING，新版本下完
 * 由 {@code DownloadTrackService#finishUpgrade} 收尾，失败退回 IN_LIBRARY。与洗版一样<b>不碰旧文件</b>，新旧两份会
 * 同时存在，页面上要说清楚。候选判据：
 * </p>
 * <ul>
 *   <li>只要本集的单集资源（{@link UpgradeScanService#matchesEpisode}，季包与区间包拒绝，理由同洗版）；</li>
 *   <li>标题或描述里有中字标识（{@link TorrentFilterEngine#hasChineseSubtitleMark}，与「外语片需中字」那条过滤同一份判据）；</li>
 *   <li>照样过一遍过滤规则（体积 / 做种 / 黑名单 / 订阅级覆盖），再按订阅的择优顺序挑一个。</li>
 * </ul>
 * <p>
 * <b>不看洗版的开关与目标质量</b>：这是用户针对这几集的明确动作，不是周期扫描；也不要求新版本在画质上更优——
 * 要的是中字，不是更高的分辨率。
 * </p>
 */
@Slf4j
@Service
public class ChineseSubtitleUpgradeService {

    /**
     * 一次最多处理几集。每集要向全部站点打一轮搜索（单站有 30 秒检索预算），前端只等 300 秒；
     * 超出的集明说「下次再点」，不静默丢掉。
     */
    static final int MAX_EPISODES = 6;

    public enum Status {
        /** 已推送带中字的版本 */
        PUSHED,
        /** 搜到了本集资源，但都没有中字标识 */
        NO_CHINESE_RELEASE,
        /** 有带中字的，但都被过滤规则挡掉了 */
        ALL_FILTERED,
        /** 没搜到本集资源 */
        NOT_FOUND,
        /** 这一集不在「已入库」状态（还在下、正在洗版、已被退回缺失） */
        NOT_IN_LIBRARY,
        /** 推送失败 */
        FAILED,
        /** 超出本次上限，没处理 */
        SKIPPED
    }

    /** @param detail 推送成功时是种子标题，其余是原因 */
    public record EpisodeResult(int episode, Status status, String detail) {
    }

    public record Result(List<EpisodeResult> results) {

        public long count(Status status) {
            return results.stream().filter(r -> r.status() == status).count();
        }
    }

    private final SearchSupplementService searchSupplementService;
    private final SubscriptionEngine subscriptionEngine;
    private final TorrentFilterEngine filterEngine;
    private final IPtFilterConfigPlusService filterConfigService;
    private final IPtTorrentBlacklistPlusService blacklistService;
    private final IPtSubscriptionEpisodePlusService episodeService;

    public ChineseSubtitleUpgradeService(SearchSupplementService searchSupplementService,
                                         SubscriptionEngine subscriptionEngine,
                                         TorrentFilterEngine filterEngine,
                                         IPtFilterConfigPlusService filterConfigService,
                                         IPtTorrentBlacklistPlusService blacklistService,
                                         IPtSubscriptionEpisodePlusService episodeService) {
        this.searchSupplementService = searchSupplementService;
        this.subscriptionEngine = subscriptionEngine;
        this.filterEngine = filterEngine;
        this.filterConfigService = filterConfigService;
        this.blacklistService = blacklistService;
        this.episodeService = episodeService;
    }

    /**
     * @param episodes 字幕体检报出来的「没有中文字幕」那几集；电影传什么都按集号 0 处理
     * @throws IllegalArgumentException 没有启用中的索引器
     */
    public Result fetch(PtSubscriptionPlus sub, List<Integer> episodes) {
        if (searchSupplementService.hasNoEnabledIndexer()) {
            throw new IllegalArgumentException("没有启用中的索引器，无法搜索。请到「索引器」页面添加或启用至少一个索引器");
        }
        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
        List<Integer> wanted = new ArrayList<>(new LinkedHashSet<>(movie ? List.of(0) : episodes));
        Map<Integer, PtSubscriptionEpisodePlus> states = episodeService.listBySubscription(sub.getId()).stream()
                .collect(Collectors.toMap(PtSubscriptionEpisodePlus::getEpisode, Function.identity(), (a, b) -> a));

        FilterCriteria criteria = FilterCriteriaFactory.build(filterConfigService.getConfig(), sub.getFilterOverride());
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());

        List<EpisodeResult> results = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            int episode = wanted.get(i);
            if (i >= MAX_EPISODES) {
                results.add(new EpisodeResult(episode, Status.SKIPPED, "一次最多处理 " + MAX_EPISODES + " 集，其余请再点一次"));
                continue;
            }
            results.add(fetchOne(sub, episode, states.get(episode), criteria, blacklist));
        }
        Result result = new Result(results);
        log.info("{} 找中字版本：{} 集已推送，{} 集没有带中字的发布，{} 集被过滤规则挡掉，{} 集没搜到{}",
                PtLogText.subject(sub), result.count(Status.PUSHED), result.count(Status.NO_CHINESE_RELEASE),
                result.count(Status.ALL_FILTERED), result.count(Status.NOT_FOUND),
                result.count(Status.SKIPPED) > 0 ? "，" + result.count(Status.SKIPPED) + " 集超出本次上限" : "");
        return result;
    }

    private EpisodeResult fetchOne(PtSubscriptionPlus sub, int episode, PtSubscriptionEpisodePlus state,
                                   FilterCriteria criteria, TorrentBlacklist blacklist) {
        // 洗版通道只认 IN_LIBRARY：先说清楚，不然要等打完一轮搜索才在推送那一步落空
        if (state == null || !SubscriptionEpisodeState.IN_LIBRARY.value().equals(state.getState())) {
            String now = state == null ? "不在这条订阅的集表里" : "当前是「" + SubscriptionEpisodeState.labelOf(state.getState()) + "」";
            return new EpisodeResult(episode, Status.NOT_IN_LIBRARY, "这一集" + now + "，只有已入库的集能换版本");
        }
        try {
            List<TorrentInfo> raw = searchSupplementService.searchAcrossIndexers(UpgradeScanService.buildKeyword(sub, episode));
            List<TorrentInfo> sameEpisode = new ArrayList<>();
            for (TorrentInfo torrent : raw) {
                subscriptionEngine.fillParsed(torrent);
                if (UpgradeScanService.matchesEpisode(sub, episode, torrent)) {
                    sameEpisode.add(torrent);
                }
            }
            if (sameEpisode.isEmpty()) {
                return new EpisodeResult(episode, Status.NOT_FOUND,
                        raw.isEmpty() ? "没搜到任何资源" : "搜到 " + raw.size() + " 个结果，但没有这一集的单集资源");
            }
            List<TorrentInfo> chinese = sameEpisode.stream().filter(ChineseSubtitleUpgradeService::hasChineseMark).toList();
            if (chinese.isEmpty()) {
                return new EpisodeResult(episode, Status.NO_CHINESE_RELEASE,
                        "搜到 " + sameEpisode.size() + " 个本集资源，标题和描述里都没有中字标识");
            }
            subscriptionEngine.markHitAndRun(chinese);
            EpisodeCountResolver.apply(chinese, sub.getTotalEpisodes(),
                    SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()));
            List<TorrentInfo> survivors = filterEngine.filter(chinese, criteria, blacklist, null);
            if (survivors.isEmpty()) {
                return new EpisodeResult(episode, Status.ALL_FILTERED,
                        chinese.size() + " 个带中字的版本都被过滤规则挡掉了（体积、做种数、黑名单等）");
            }
            TorrentInfo best = filterEngine.pickBest(survivors, criteria);
            PushOutcome outcome = subscriptionEngine.pushUpgradeOutcome(sub, episode, List.of(best));
            if (!outcome.pushed()) {
                return new EpisodeResult(episode, Status.FAILED, outcome.reason());
            }
            log.info("{} 找中字版本：已推送 {}", PtLogText.subject(sub, episode, null), best.getTitle());
            return new EpisodeResult(episode, Status.PUSHED, best.getTitle());
        } catch (Exception e) {
            log.warn("{} 找中字版本失败：{}", PtLogText.subject(sub, episode, null), e.getMessage(), e);
            return new EpisodeResult(episode, Status.FAILED, e.getMessage());
        }
    }

    static boolean hasChineseMark(TorrentInfo torrent) {
        return TorrentFilterEngine.hasChineseSubtitleMark(torrent.getTitle())
                || TorrentFilterEngine.hasChineseSubtitleMark(torrent.getDescription());
    }
}
