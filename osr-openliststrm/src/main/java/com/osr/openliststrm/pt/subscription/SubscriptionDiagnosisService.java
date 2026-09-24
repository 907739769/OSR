package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.filter.RejectCode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订阅「一键诊断」：这条订阅里每个已播出却还缺着的集，最近一轮搜了什么、为什么没推成。
 * <p>
 * 与缺集体检（{@code pt/health/}）的分工：体检按「逾期满 N 天」全站扫一遍、给诊断<b>标签</b>；
 * 这里只看一条订阅、不设逾期门槛（刚播一两天还缺的集也列），并把搜索日志里逐个候选的
 * 淘汰原因<b>按原因计数</b>摆出来——「候选被过滤」只说明方向，「5 个分辨率不符、2 个非免费」
 * 才告诉用户该松哪条规则。
 * <p>
 * <b>只读、不打外部请求</b>：数据全部来自集表与 {@code pt_search_log}（每条订阅只保留几百行），
 * 页面点一下不该变成一轮索引器搜索——要搜，页面上有「立即补搜」。
 *
 * @author Jack
 */
@Service
public class SubscriptionDiagnosisService {

    /** 最多列几集。长篇动画一次缺几十集，全列出来反而找不到重点 */
    static final int MAX_EPISODES = 30;

    /** 同一集的日志，离最新一条多远以内算「同一轮搜索」 */
    static final long ROUND_WINDOW_MILLIS = 10 * 60_000L;

    /** 淘汰原因最多列几类 */
    private static final int TOP_REASONS = 4;

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final IPtSearchLogPlusService logService;
    private final IPtIndexerPlusService indexerService;

    public SubscriptionDiagnosisService(IPtSubscriptionPlusService subscriptionService,
                                        IPtSubscriptionEpisodePlusService episodeService,
                                        IPtSearchLogPlusService logService,
                                        IPtIndexerPlusService indexerService) {
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.logService = logService;
        this.indexerService = indexerService;
    }

    /**
     * @throws IllegalArgumentException 订阅不存在
     */
    public Diagnosis diagnose(Integer subId) {
        return diagnose(subId, LocalDate.now());
    }

    /** today 做成参数，理由同 {@code EpisodeHealthService#scan(LocalDate)} */
    Diagnosis diagnose(Integer subId, LocalDate today) {
        PtSubscriptionPlus sub = subscriptionService.getById(subId);
        if (sub == null) {
            throw new IllegalArgumentException("订阅不存在");
        }
        List<String> notes = subscriptionNotes(sub);

        List<PtSubscriptionEpisodePlus> pending = episodeService.list(new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                        .eq(PtSubscriptionEpisodePlus::getSubId, subId)
                        .in(PtSubscriptionEpisodePlus::getState, SubscriptionEpisodeState.MISSING.value(),
                                SubscriptionEpisodeState.BLOCKED.value(), SubscriptionEpisodeState.IN_FLIGHT.value())
                        .orderByAsc(PtSubscriptionEpisodePlus::getEpisode))
                .stream()
                .filter(ep -> aired(ep, today))
                .toList();
        if (pending.isEmpty()) {
            return new Diagnosis(sub.getId(), sub.getTitle(), notes, List.of(), 0);
        }

        List<PtSearchLogPlus> logs = logService.list(new LambdaQueryWrapper<PtSearchLogPlus>()
                .eq(PtSearchLogPlus::getSubId, subId));
        Map<Integer, List<PtSearchLogPlus>> byEpisode = logs.stream()
                .filter(l -> l.getEpisode() != null)
                .collect(Collectors.groupingBy(PtSearchLogPlus::getEpisode));
        List<PtSearchLogPlus> seasonPackLogs = byEpisode.getOrDefault(SubscriptionMatcher.SEASON_PACK, List.of());

        List<EpisodeDiagnosis> episodes = new ArrayList<>();
        for (PtSubscriptionEpisodePlus ep : pending.subList(0, Math.min(pending.size(), MAX_EPISODES))) {
            List<PtSearchLogPlus> related = new ArrayList<>(byEpisode.getOrDefault(ep.getEpisode(), List.of()));
            related.addAll(seasonPackLogs);
            episodes.add(diagnoseEpisode(sub, ep, related));
        }
        return new Diagnosis(sub.getId(), sub.getTitle(), notes, episodes, pending.size());
    }

    /** 订阅级的前提问题：这些不解决，逐集看什么都白看 */
    private List<String> subscriptionNotes(PtSubscriptionPlus sub) {
        List<String> notes = new ArrayList<>();
        if (!SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            notes.add("订阅当前不在「订阅中」状态，RSS 与补搜都不会为它工作");
        }
        if (indexerService.listEnabled().isEmpty()) {
            notes.add("没有启用中的索引器，任何搜索都不会发出去");
        }
        if (!"1".equals(sub.getAutoSearch())) {
            notes.add("未开启自动补搜：缺的集只能等 RSS 碰上新发布的种子，已经发布过的资源不会被搜回来");
        }
        if (StringUtils.isNotBlank(sub.getFilterOverride())) {
            notes.add("这条订阅有自己的过滤规则覆盖，淘汰原因可能来自覆盖项而不是全局规则");
        }
        return notes;
    }

    private EpisodeDiagnosis diagnoseEpisode(PtSubscriptionPlus sub, PtSubscriptionEpisodePlus ep,
                                             List<PtSearchLogPlus> related) {
        String state = ep.getState();
        String label = episodeLabel(sub, ep.getEpisode());
        if (SubscriptionEpisodeState.IN_FLIGHT.value().equals(state)) {
            return new EpisodeDiagnosis(ep.getEpisode(), label, state, null, null, 0, 0, List.of(),
                    "已推送下载器，正在下载或等待入库；长时间不动到「下载记录」页看这条种子");
        }
        Date latest = related.stream().map(SubscriptionDiagnosisService::timeOf).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        if (latest == null) {
            String summary = SubscriptionEpisodeState.BLOCKED.value().equals(state)
                    ? "连续失败已熔断，自动重试已停止；在进度里重置这一集，或到下载记录页手动重试"
                    : "还没有搜索记录" + ("1".equals(sub.getAutoSearch()) ? "（下一轮自动补搜会搜）" : "，可以点「立即补搜」试一次");
            return new EpisodeDiagnosis(ep.getEpisode(), label, state, null, null, 0, 0, List.of(), summary);
        }

        List<PtSearchLogPlus> round = related.stream()
                .filter(l -> {
                    Date t = timeOf(l);
                    return t != null && latest.getTime() - t.getTime() <= ROUND_WINDOW_MILLIS;
                })
                .toList();
        List<PtSearchLogPlus> candidates = round.stream().filter(l -> StringUtils.isNotBlank(l.getTorrentTitle())).toList();
        int accepted = (int) candidates.stream().filter(l -> "1".equals(l.getAccepted())).count();
        List<ReasonCount> reasons = candidates.stream()
                .filter(l -> !"1".equals(l.getAccepted()))
                .collect(Collectors.groupingBy(l -> RejectCode.labelOf(l.getReasonCode()), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_REASONS)
                .map(e -> new ReasonCount(e.getKey(), e.getValue().intValue()))
                .toList();
        String source = sourceOf(round);

        StringBuilder summary = new StringBuilder();
        summary.append("最近一次").append(source).append("（").append(format(latest)).append("）");
        if (candidates.isEmpty()) {
            // 没有候选时日志里通常只有一条摘要（如「无可用下载器」「未搜到候选」），原样给出
            String note = round.stream().map(PtSearchLogPlus::getReason).filter(StringUtils::isNotBlank)
                    .findFirst().orElse("没有搜到任何候选");
            summary.append("：").append(note);
        } else {
            summary.append("：").append(candidates.size()).append(" 个候选");
            if (!reasons.isEmpty()) {
                summary.append("，").append(reasons.stream()
                        .map(r -> r.count() + " 个「" + r.label() + "」").collect(Collectors.joining("、")));
            }
            if (accepted > 0) {
                summary.append("；").append(accepted).append(" 个通过了过滤")
                        .append(SubscriptionEpisodeState.BLOCKED.value().equals(state)
                                ? "，但推送后连续失败已熔断" : "，但没能推送成功或下载失败后退回了缺失");
            }
        }
        return new EpisodeDiagnosis(ep.getEpisode(), label, state, latest, source.trim(), candidates.size(), accepted,
                reasons, summary.toString());
    }

    /** 日志里 source 是 RSS / SUPPLEMENT，一轮里两种都有时说「搜索」 */
    private static String sourceOf(List<PtSearchLogPlus> round) {
        boolean rss = round.stream().anyMatch(l -> "RSS".equals(l.getSource()));
        boolean supplement = round.stream().anyMatch(l -> !"RSS".equals(l.getSource()));
        return rss && !supplement ? " RSS 匹配" : !rss && supplement ? "补搜" : "搜索";
    }

    /** 未播出的集不诊断；播出日期未知的按已播出处理，口径同 {@code SearchSupplementService#aired} */
    private static boolean aired(PtSubscriptionEpisodePlus ep, LocalDate today) {
        Date airDate = ep.getAirDate();
        if (airDate == null) {
            return true;
        }
        LocalDate d = airDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return !d.isAfter(today);
    }

    private static String episodeLabel(PtSubscriptionPlus sub, Integer episode) {
        return SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()) ? "正片" : "第 " + episode + " 集";
    }

    /**
     * 日志的 create_time 在 MP 实体上是字符串（{@code MyMetaObjectHandler} 按 yyyy-MM-dd HH:mm:ss 填），
     * 读回来时部分驱动会带上 {@code .0}，只取前 19 位解析。解析不了当作没有时间，不参与「最近一轮」。
     */
    static Date timeOf(PtSearchLogPlus log) {
        String raw = log.getCreateTime();
        if (raw == null || raw.length() < 19) {
            return null;
        }
        try {
            return Date.from(LocalDateTime.parse(raw.substring(0, 19), LOG_TIME)
                    .atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String format(Date date) {
        return new SimpleDateFormat("MM-dd HH:mm").format(date);
    }

    /**
     * 诊断结果。
     *
     * @param episodes     逐集诊断（最多 {@link #MAX_EPISODES} 集）
     * @param pendingTotal 已播出仍未入库的集总数，超出上限时页面据此说「还有 N 集没列」
     */
    public record Diagnosis(Integer subId, String title, List<String> notes, List<EpisodeDiagnosis> episodes,
                            int pendingTotal) {
    }

    /**
     * 一集的诊断。
     *
     * @param lastSearchTime 最近一轮搜索的时间，没搜过为 null
     * @param candidates     最近一轮的候选数
     * @param accepted       其中通过过滤的
     * @param reasons        淘汰原因计数，从多到少
     * @param summary        给人看的一句话
     */
    public record EpisodeDiagnosis(Integer episode, String label, String state, Date lastSearchTime, String source,
                                   int candidates, int accepted, List<ReasonCount> reasons, String summary) {
    }

    public record ReasonCount(String label, int count) {
    }
}
