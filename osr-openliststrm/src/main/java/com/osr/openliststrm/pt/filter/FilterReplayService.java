package com.osr.openliststrm.pt.filter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 过滤规则的「历史回放」：拿最近搜到过的候选，把<b>已保存的规则</b>与<b>编辑中的草稿</b>各评估一遍，
 * 列出结论变了的——改一条规则之前先看清它会多放进来什么、多挡掉什么。
 * <p>
 * <b>比的是「现在的规则」与「草稿」，不是「当时的结论」与「草稿」</b>：日志里的通过/淘汰是当时的
 * 规则判的，其间规则可能已经改过好几轮，拿它当基线会把早就生效的修改也算成这次的影响。
 * <p>
 * <b>旧日志只能按标题比</b>：20260803 之前的日志没记体积、做种数、下载系数与 H&R，
 * 这些行两边都把这四个维度中和掉（阈值清零、仅免费与规避 H&R 关掉，订阅覆盖里的同名键一并去掉），
 * 只比分辨率、来源、关键词、发布组、质量标签这些标题维度；结果里单独报出有几条是这么比的。
 * <p>
 * 只读，不打外部请求：候选全部来自 {@code pt_search_log}。
 *
 * @author Jack
 */
@Service
public class FilterReplayService {

    /** 最多回放多少个不同的候选。日志每订阅保留几百条，全站几十条订阅就是上万行，没必要全算 */
    static final int MAX_CANDIDATES = 2000;

    /** 每一类变化最多带回几个例子 */
    static final int MAX_EXAMPLES = 30;

    /** 旧日志没有的数值维度：中和时从全局配置与订阅覆盖里一起去掉 */
    static final Set<String> NUMERIC_KEYS = Set.of("minSeeders", "minSize", "maxSize", "freeOnly", "avoidHitAndRun");

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IPtSearchLogPlusService logService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtFilterConfigPlusService filterConfigService;
    private final IPtTorrentBlacklistPlusService blacklistService;
    private final SubscriptionEngine subscriptionEngine;
    private final TorrentFilterEngine filterEngine;

    public FilterReplayService(IPtSearchLogPlusService logService, IPtSubscriptionPlusService subscriptionService,
                               IPtFilterConfigPlusService filterConfigService,
                               IPtTorrentBlacklistPlusService blacklistService,
                               SubscriptionEngine subscriptionEngine, TorrentFilterEngine filterEngine) {
        this.logService = logService;
        this.subscriptionService = subscriptionService;
        this.filterConfigService = filterConfigService;
        this.blacklistService = blacklistService;
        this.subscriptionEngine = subscriptionEngine;
        this.filterEngine = filterEngine;
    }

    /**
     * @param draft 编辑中的规则草稿（未保存）
     * @param days  回放最近几天的候选，限定在 1~30
     */
    public ReplayResult replay(PtFilterConfigPlus draft, int days) {
        int window = Math.max(1, Math.min(30, days));
        String since = LocalDateTime.now().minusDays(window).format(LOG_TIME);
        List<PtSearchLogPlus> logs = logService.list(new LambdaQueryWrapper<PtSearchLogPlus>()
                .isNotNull(PtSearchLogPlus::getTorrentTitle)
                .ge(PtSearchLogPlus::getCreateTime, since)
                .orderByDesc(PtSearchLogPlus::getId));

        // 同一个候选（站点 + 标题 + 订阅）在一轮轮搜索里会被记很多次，只取最新一条
        Map<String, PtSearchLogPlus> distinct = new LinkedHashMap<>();
        for (PtSearchLogPlus log : logs) {
            distinct.putIfAbsent(log.getSubId() + "|" + log.getIndexerId() + "|" + log.getTorrentTitle(), log);
            if (distinct.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        if (distinct.isEmpty()) {
            return new ReplayResult(window, 0, 0, 0, new ChangeGroup(0, List.of()), new ChangeGroup(0, List.of()));
        }

        Map<Integer, PtSubscriptionPlus> subs = subscriptionService.listByIds(
                        distinct.values().stream().map(PtSearchLogPlus::getSubId).distinct().toList())
                .stream().collect(Collectors.toMap(PtSubscriptionPlus::getId, Function.identity(), (a, b) -> a));
        PtFilterConfigPlus saved = filterConfigService.getConfig();
        PtFilterConfigPlus savedNeutral = neutralize(saved);
        PtFilterConfigPlus draftNeutral = neutralize(draft);
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());

        int titleOnly = 0;
        List<Change> nowAccepted = new ArrayList<>();
        List<Change> nowRejected = new ArrayList<>();
        int newlyAcceptedTotal = 0;
        int newlyRejectedTotal = 0;
        for (PtSearchLogPlus log : distinct.values()) {
            PtSubscriptionPlus sub = subs.get(log.getSubId());
            boolean full = log.getTorrentSize() != null;
            if (!full) {
                titleOnly++;
            }
            String override = sub == null ? null : sub.getFilterOverride();
            FilterCriteria before = FilterCriteriaFactory.build(full ? saved : savedNeutral,
                    full ? override : neutralizeOverride(override));
            FilterCriteria after = FilterCriteriaFactory.build(full ? draft : draftNeutral,
                    full ? override : neutralizeOverride(override));
            TorrentInfo torrent = torrentOf(log, full);
            TorrentFilterEngine.Verdict b = filterEngine.evaluate(List.of(torrent), before, blacklist, null).get(0);
            TorrentFilterEngine.Verdict a = filterEngine.evaluate(List.of(torrent), after, blacklist, null).get(0);
            if (b.accepted() == a.accepted()) {
                continue;
            }
            String subTitle = sub == null ? "（订阅已删除）" : sub.getTitle();
            if (a.accepted()) {
                newlyAcceptedTotal++;
                if (nowAccepted.size() < MAX_EXAMPLES) {
                    nowAccepted.add(new Change(log.getTorrentTitle(), subTitle, !full, labelOf(b)));
                }
            } else {
                newlyRejectedTotal++;
                if (nowRejected.size() < MAX_EXAMPLES) {
                    nowRejected.add(new Change(log.getTorrentTitle(), subTitle, !full, labelOf(a)));
                }
            }
        }
        return new ReplayResult(window, distinct.size(), titleOnly, newlyAcceptedTotal + newlyRejectedTotal,
                new ChangeGroup(newlyAcceptedTotal, nowAccepted), new ChangeGroup(newlyRejectedTotal, nowRejected));
    }

    private TorrentInfo torrentOf(PtSearchLogPlus log, boolean full) {
        TorrentInfo torrent = new TorrentInfo();
        torrent.setTitle(log.getTorrentTitle());
        torrent.setGuid("replay-" + log.getId());
        torrent.setIndexerId(log.getIndexerId());
        if (full) {
            torrent.setSize(log.getTorrentSize());
            torrent.setSeeders(log.getSeeders() == null ? 0 : log.getSeeders());
            torrent.setDownloadVolumeFactor(log.getDownloadFactor() == null ? 1.0 : log.getDownloadFactor());
            torrent.setHitAndRun("1".equals(log.getHitAndRun()));
        }
        subscriptionEngine.fillParsed(torrent);
        EpisodeCountResolver.apply(List.of(torrent), null, false);
        return torrent;
    }

    /** 数值维度清零的副本：体积上下限 0 = 不限，做种数下限 0，仅免费与规避 H&R 关掉 */
    static PtFilterConfigPlus neutralize(PtFilterConfigPlus config) {
        PtFilterConfigPlus copy = new PtFilterConfigPlus();
        if (config != null) {
            BeanUtils.copyProperties(config, copy);
        }
        copy.setMinSeeders(0);
        copy.setMinSize(0L);
        copy.setMaxSize(0L);
        copy.setFreeOnly("0");
        copy.setAvoidHitAndRun("0");
        return copy;
    }

    /** 订阅覆盖里同名的数值键也要去掉，否则中和的全局值会被覆盖回去 */
    static String neutralizeOverride(String override) {
        if (StringUtils.isBlank(override)) {
            return override;
        }
        try {
            JSONObject patch = JSON.parseObject(override);
            if (patch == null) {
                return override;
            }
            NUMERIC_KEYS.forEach(patch::remove);
            return patch.toJSONString();
        } catch (JSONException e) {
            return override;
        }
    }

    private static String labelOf(TorrentFilterEngine.Verdict verdict) {
        return verdict.accepted() ? "通过" : verdict.rejectCode() == null ? "淘汰" : verdict.rejectCode().label();
    }

    /**
     * 回放结果。
     *
     * @param days          回放的天数
     * @param evaluated     参与回放的不同候选数
     * @param titleOnly     其中只能按标题维度比较的（旧日志没有体积等画像）
     * @param changed       结论变化的总数
     * @param newlyAccepted 草稿下会通过、现在被淘汰的
     * @param newlyRejected 草稿下会被淘汰、现在能通过的
     */
    public record ReplayResult(int days, int evaluated, int titleOnly, int changed,
                               ChangeGroup newlyAccepted, ChangeGroup newlyRejected) {
    }

    /**
     * @param total    这一类变化的总数
     * @param examples 例子（最多 {@link #MAX_EXAMPLES} 个）
     */
    public record ChangeGroup(int total, List<Change> examples) {
    }

    /**
     * 一个结论变了的候选。
     *
     * @param titleOnly 只按标题维度比较（旧日志）
     * @param reason    淘汰的那一侧的原因：新通过的写「现在为什么被淘汰」，新淘汰的写「草稿下为什么被淘汰」
     */
    public record Change(String torrentTitle, String subscriptionTitle, boolean titleOnly, String reason) {
    }
}
