package com.osr.openliststrm.pt.upgrade;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import com.osr.openliststrm.pt.PtLogText;
import com.osr.openliststrm.pt.filter.EpisodeCountResolver;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.FilterCriteriaFactory;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 洗版扫描：找出已入库但质量未达目标的集，搜索更好的版本并推送。
 * <p>
 * <b>第一期只做"识别 + 下载 + 通知"，不碰旧文件。</b>OSR 从不删种，洗版落地后新旧两个
 * 版本会同时躺在下载目录里，清理由用户手动完成——自动清理留到第二期，且必须先过 H&R
 * 达标检查（删掉还在考核期内的旧种子的文件，等于亲手制造一次 H&R 记过）。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class UpgradeScanService {

    private static final String STATE_IN_LIBRARY = SubscriptionEpisodeState.IN_LIBRARY.value();
    private static final String STATE_UPGRADING = SubscriptionEpisodeState.UPGRADING.value();

    private final IPtUpgradeConfigPlusService upgradeConfigService;
    private final IPtFilterConfigPlusService filterConfigService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final IPtTorrentBlacklistPlusService blacklistService;
    private final SearchSupplementService searchSupplementService;
    private final SubscriptionEngine subscriptionEngine;
    private final TorrentFilterEngine filterEngine;
    private final UpgradeEvaluator evaluator;

    public UpgradeScanService(IPtUpgradeConfigPlusService upgradeConfigService,
                              IPtFilterConfigPlusService filterConfigService,
                              IPtSubscriptionPlusService subscriptionService,
                              IPtSubscriptionEpisodePlusService episodeService,
                              IPtTorrentBlacklistPlusService blacklistService,
                              SearchSupplementService searchSupplementService,
                              SubscriptionEngine subscriptionEngine,
                              TorrentFilterEngine filterEngine,
                              UpgradeEvaluator evaluator) {
        this.upgradeConfigService = upgradeConfigService;
        this.filterConfigService = filterConfigService;
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.blacklistService = blacklistService;
        this.searchSupplementService = searchSupplementService;
        this.subscriptionEngine = subscriptionEngine;
        this.filterEngine = filterEngine;
        this.evaluator = evaluator;
    }

    /**
     * 跑一轮洗版扫描。顶层不抛异常：单集失败不影响其它集，整轮失败不影响调度器。
     *
     * @return 本轮成功推送的洗版下载数
     */
    public int run() {
        UpgradeCriteria criteria = UpgradeCriteriaFactory.build(
                upgradeConfigService.getConfig(), filterConfigService.getConfig());
        if (!criteria.active()) {
            log.debug("洗版未启用或未配置目标质量，跳过本轮扫描");
            return 0;
        }

        // 全局在途洗版闸门：缺集是刚需，洗版是锦上添花。没有这道闸，一次大规模洗版会把
        // 下载器名额占满，新剧的更新全被堵在门外。
        int inFlight = countInFlightUpgrades();
        int budget = criteria.maxConcurrent() - inFlight;
        if (budget <= 0) {
            log.debug("在途洗版 {} 个已达上限 {}，跳过本轮扫描", inFlight, criteria.maxConcurrent());
            return 0;
        }

        List<PtSubscriptionEpisodePlus> candidates = listUpgradableEpisodes();
        if (candidates.isEmpty()) {
            return 0;
        }
        log.info("洗版扫描：{} 个已入库集待评估，本轮可用名额 {}", candidates.size(), budget);

        int pushed = 0;
        for (PtSubscriptionEpisodePlus episode : candidates) {
            if (pushed >= budget) {
                log.info("本轮洗版名额已用尽（{}），其余集留待下一轮", budget);
                break;
            }
            try {
                if (tryUpgrade(episode, criteria)) {
                    pushed++;
                }
            } catch (Exception e) {
                log.warn("{} 第 {} 集洗版失败：{}",
                        PtLogText.subject(episode.getSubId()), episode.getEpisode(), e.getMessage());
            }
        }
        return pushed;
    }

    /**
     * 评估并尝试升级一集。
     * <p>
     * 三道关卡依次是：有没有基线 → 是不是已经够好了 → 搜到的候选里有没有严格更优的。
     * 前两关的结论会写回 {@code upgrade_state}，让下一轮扫描能直接跳过，
     * 不必每轮都对全部已入库集重新判一遍。
     * </p>
     */
    private boolean tryUpgrade(PtSubscriptionEpisodePlus episode, UpgradeCriteria criteria) {
        QualityProfile current = QualityProfile.fromJson(episode.getQuality());
        if (current == null) {
            markUpgradeState(episode, UpgradeState.NO_BASELINE);
            return false;
        }
        if (evaluator.reachedTarget(current, criteria)) {
            markUpgradeState(episode, UpgradeState.REACHED);
            // 这里还没加载订阅（下面几行才查），而「已达目标」是最常见的分支，
            // 为一条 DEBUG 多打一次库不划算——格式统一成 订阅[#id]，显式表示此处没有剧名
            log.debug("{} 第 {} 集已达目标质量（{}），不再参与洗版",
                    PtLogText.subject(episode.getSubId()), episode.getEpisode(), current.describe());
            return false;
        }

        PtSubscriptionPlus sub = subscriptionService.getById(episode.getSubId());
        if (sub == null || !upgradable(sub)) {
            return false;
        }

        List<TorrentInfo> better = findBetterCandidates(sub, episode, current, criteria);
        if (better.isEmpty()) {
            return false;
        }
        TorrentInfo best = evaluator.pickBest(better, QualityProfile::from, criteria);
        boolean pushed = subscriptionEngine.pushUpgrade(sub, episode.getEpisode(), List.of(best));
        if (pushed) {
            log.info("{} 洗版已推送：{} → {}（{}）",
                    PtLogText.subject(sub, episode.getEpisode(), null),
                    current.describe(), QualityProfile.from(best).describe(),
                    evaluator.describeUpgrade(QualityProfile.from(best), current, criteria));
        }
        return pushed;
    }

    /**
     * 搜索并筛出严格优于现有版本的候选。
     * <p>
     * 候选先过 {@link TorrentFilterEngine}（体积/做种/黑名单/画质硬过滤那一套照旧生效），
     * 再过 {@link UpgradeEvaluator}。两层顺序不能反：过滤引擎会淘汰掉根本不该下载的种子，
     * 让它们先出局能避免"升级判定说更好、真去推送时又被过滤掉"这种自相矛盾。
     * </p>
     */
    private List<TorrentInfo> findBetterCandidates(PtSubscriptionPlus sub, PtSubscriptionEpisodePlus episode,
                                                   QualityProfile current, UpgradeCriteria criteria) {
        String keyword = buildKeyword(sub, episode.getEpisode());
        List<TorrentInfo> raw = searchSupplementService.searchAcrossIndexers(keyword);
        if (raw.isEmpty()) {
            return List.of();
        }
        for (TorrentInfo torrent : raw) {
            subscriptionEngine.fillParsed(torrent);
        }

        // 只保留真正对应这一集的候选：searchAcrossIndexers 是模糊全文搜索，
        // 返回的东西可能是别的季、别的集、甚至别的剧
        List<TorrentInfo> sameEpisode = new ArrayList<>();
        for (TorrentInfo torrent : raw) {
            if (matchesEpisode(sub, episode.getEpisode(), torrent)) {
                sameEpisode.add(torrent);
            }
        }
        if (sameEpisode.isEmpty()) {
            return List.of();
        }

        PtFilterConfigPlus globalConfig = filterConfigService.getConfig();
        FilterCriteria filterCriteria = FilterCriteriaFactory.build(globalConfig, sub.getFilterOverride());
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());
        // 体积阈值按每集判定时要先知道候选覆盖多少集。洗版候选虽然被 matchesEpisode 收窄成
        // 单集资源（区间包与季包都会被它挡掉），仍照样跑一遍：口径与另外两条链路保持一致，
        // 将来 matchesEpisode 放宽时这里不必再补一次
        EpisodeCountResolver.apply(sameEpisode, sub.getTotalEpisodes(),
                SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()));
        List<TorrentInfo> survivors = filterEngine.filter(sameEpisode, filterCriteria, blacklist, null);

        List<TorrentInfo> better = new ArrayList<>();
        for (TorrentInfo torrent : survivors) {
            if (evaluator.isUpgrade(QualityProfile.from(torrent), current, criteria)) {
                better.add(torrent);
            }
        }
        log.debug("{} 洗版搜索：原始 {} 条，本集 {} 条，过滤后 {} 条，严格更优 {} 条",
                PtLogText.subject(sub, episode.getEpisode(), null),
                raw.size(), sameEpisode.size(), survivors.size(), better.size());
        return better;
    }

    /**
     * 候选是否对应目标订阅的目标集。
     * <p>
     * 电影只核对标题与年份；剧集核对标题、季号与集号，且<b>拒绝季包与区间包</b>——
     * 洗版是"把这一集换个更好的版本"，用一个季包去覆盖会连带动到那些没打算升级的集，
     * 它们的质量基线根本没被比较过。
     * </p>
     */
    private boolean matchesEpisode(PtSubscriptionPlus sub, int episode, TorrentInfo torrent) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return torrent.getParsedSeason() == null && torrent.getParsedEpisode() == null
                    && StringUtils.isNotBlank(torrent.getParsedYear())
                    && torrent.getParsedYear().equals(sub.getYear());
        }
        if (torrent.getParsedSeason() == null || !torrent.getParsedSeason().equals(sub.getSeason())) {
            return false;
        }
        if (torrent.getParsedEpisode() == null || torrent.getParsedEpisode() != episode) {
            return false;
        }
        // 区间包（如 S01E01-E03）排除：parsedEpisodeEnd 严格大于起始集号才算区间
        return torrent.getParsedEpisodeEnd() == null
                || torrent.getParsedEpisodeEnd() <= torrent.getParsedEpisode();
    }

    private String buildKeyword(PtSubscriptionPlus sub, int episode) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return sub.getTitle();
        }
        return sub.getTitle() + " S" + pad(sub.getSeason()) + "E" + pad(episode);
    }

    private String pad(Integer number) {
        int n = number == null ? 0 : number;
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /**
     * 这条订阅是否参与洗版。
     * <p>
     * 订阅级开关关掉即不参与。状态上只排除 PAUSED——暂停意味着用户主动叫停了这条订阅的
     * 一切自动动作，洗版也不例外；而<b>已完结（COMPLETED）的订阅仍要参与</b>：
     * 集齐了不代表质量到位，"追完的老剧慢慢换成 4K"正是洗版最典型的用法，
     * 把 COMPLETED 排除掉会让这个功能对最需要它的那批订阅完全失效。
     * </p>
     */
    private boolean upgradable(PtSubscriptionPlus sub) {
        if ("0".equals(sub.getUpgradeEnabled())) {
            return false;
        }
        return !SubscriptionService.STATUS_PAUSED.equals(sub.getStatus());
    }

    /** 当前在途的洗版下载数 = 处于 UPGRADING 的集数 */
    private int countInFlightUpgrades() {
        return (int) episodeService.count(new QueryWrapper<PtSubscriptionEpisodePlus>()
                .eq("state", STATE_UPGRADING));
    }

    /**
     * 待评估的已入库集：状态 IN_LIBRARY，且 {@code upgrade_state} 不是终态。
     * <p>
     * REACHED（已够好）与 NO_BASELINE（不知道库里是什么）都不必每轮重新评估，直接排除，
     * 否则一个上千集的媒体库每轮都要把全部集反序列化一遍画像。
     * </p>
     */
    private List<PtSubscriptionEpisodePlus> listUpgradableEpisodes() {
        return episodeService.list(new QueryWrapper<PtSubscriptionEpisodePlus>()
                .eq("state", STATE_IN_LIBRARY)
                .and(w -> w.isNull("upgrade_state").or().eq("upgrade_state", UpgradeState.PENDING.value())));
    }

    private void markUpgradeState(PtSubscriptionEpisodePlus episode, UpgradeState state) {
        PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
        set.setUpgradeState(state.value());
        episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                .eq("id", episode.getId())
                .eq("state", STATE_IN_LIBRARY));
    }
}
