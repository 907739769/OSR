package com.osr.openliststrm.pt.health;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.filter.RejectCode;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthItem;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthReport;
import com.osr.openliststrm.pt.health.dto.SubscriptionHealthItem;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.task.DownloadTrackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 缺集体检：把「哪些剧还缺着、缺了多久、为什么还缺」一次性算出来。
 * <p>
 * <b>为什么需要这一层。</b>自动补搜（{@code AutoSearchService}）只处理开着
 * {@code auto_search} 的订阅，而这个开关的库默认值是 {@code '0'}、建订阅时也不会自动打开。
 * 于是「订阅建完就没再管过、集一直缺着」这个最常见的场景在改造前<b>一条提醒都没有</b>：
 * 落空通知只对开着开关的订阅发，{@code StuckEpisodeSweepService} 管的是「下完了没入库」，
 * 追剧日历只按日期铺格子、不回答"这一格为什么还是灰的"。
 * </p>
 * <p>
 * <b>这是一次纯查询，不打任何外部请求。</b>判据全部来自已经落库的字段——播出日期由
 * {@code EpisodeAirDateSyncTask} 每 12 小时维护，补搜状态由 {@code AutoSearchService} 写入，
 * 文件确认位由 {@code DownloadTrackService} 顺带落下。与 {@code PtCalendarService} 同一个姿势：
 * 页面刷新不该变成一轮 TMDb/索引器调用。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class EpisodeHealthService {

    /** 「压根没搜到候选」的落空指纹取值，与 {@code AutoSearchService} 里的常量同值 */
    static final String NO_CANDIDATE_SIGN = "NO_CANDIDATE";

    private final IPtSubscriptionEpisodePlusService episodeService;
    private final IPtSubscriptionPlusService subscriptionService;

    /**
     * 播出多少天后仍未入库才算「逾期」。
     * <p>
     * 默认 3 天是刻意的宽松：热门剧的资源通常几小时内就有，但冷门剧、原盘小组、
     * 需要等字幕的片子拖上一两天是常态。判早了会把一批正在正常走流程的集报成问题，
     * 而一个总在误报的看板用户看两次就不看了；判晚了只是让真正的缺集晚两天露头，
     * 反正它已经缺了。
     * </p>
     */
    private final int overdueDays;

    public EpisodeHealthService(IPtSubscriptionEpisodePlusService episodeService,
                                IPtSubscriptionPlusService subscriptionService,
                                @Value("${pt.health.overdue-days:3}") int overdueDays) {
        this.episodeService = episodeService;
        this.subscriptionService = subscriptionService;
        this.overdueDays = Math.max(0, overdueDays);
    }

    public int getOverdueDays() {
        return overdueDays;
    }

    /** 扫一遍全部 ACTIVE 订阅。today 取系统当天 */
    public List<SubscriptionHealth> scan() {
        return scan(LocalDate.now());
    }

    /**
     * 扫一遍全部 ACTIVE 订阅，按订阅聚合。
     * <p>
     * {@code today} 显式传入而不是内部取 {@code LocalDate.now()}，是为了让「逾期天数」
     * 这套算术能被测试钉住。刻意<b>不</b>为此加第二个构造器注入 {@code Clock}：
     * 一个 bean 有多个构造器时 Spring 不会自己挑，没标 {@code @Autowired} 就退回去找默认构造器、
     * 找不到就整个应用装配失败——而单测直接 new，绕开 Spring，全绿。这个坑
     * {@code LoginAttemptService} 踩过一次。
     * </p>
     */
    List<SubscriptionHealth> scan(LocalDate today) {
        Date airedBefore = toDate(today.minusDays(overdueDays));
        List<PtSubscriptionEpisodePlus> candidates = episodeService.listHealthCandidates(airedBefore);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Integer> subIds = candidates.stream()
                .map(PtSubscriptionEpisodePlus::getSubId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Integer, PtSubscriptionPlus> subs = subscriptionService.listByIds(subIds).stream()
                .collect(Collectors.toMap(PtSubscriptionPlus::getId, Function.identity(), (a, b) -> a));

        // 保持 SQL 给出的 subId/episode 升序，聚合后订阅之间的先后由调用方按逾期天数重排
        Map<Integer, List<PtSubscriptionEpisodePlus>> bySub = candidates.stream()
                .filter(e -> subs.containsKey(e.getSubId()))
                .collect(Collectors.groupingBy(PtSubscriptionEpisodePlus::getSubId,
                        LinkedHashMap::new, Collectors.toList()));

        List<SubscriptionHealth> result = new ArrayList<>(bySub.size());
        for (Map.Entry<Integer, List<PtSubscriptionEpisodePlus>> entry : bySub.entrySet()) {
            PtSubscriptionPlus sub = subs.get(entry.getKey());
            List<EpisodeHealthItem> items = entry.getValue().stream()
                    .map(ep -> toItem(ep, sub, today))
                    .toList();
            result.add(new SubscriptionHealth(sub, items));
        }
        return result;
    }

    /**
     * 生成给页面用的报告。
     *
     * @param accessible 归属过滤：只保留当前用户能看到的订阅。放在这里而不是 SQL 里，
     *                   是因为「谁能看什么」的判据在 Controller 层（管理员看全部、
     *                   其余人看自己的与无归属的），服务层不该知道当前登录用户是谁
     */
    public EpisodeHealthReport report(Predicate<PtSubscriptionPlus> accessible) {
        List<SubscriptionHealth> scanned = scan().stream()
                .filter(h -> accessible.test(h.subscription()))
                .sorted(bySeverity())
                .toList();
        if (scanned.isEmpty()) {
            return EpisodeHealthReport.empty(overdueDays);
        }
        Map<String, Integer> bucketCounts = countBy(scanned, EpisodeHealthItem::bucket, EpisodeHealthBucket.class);
        Map<String, Integer> diagnosisCounts = countBy(scanned, EpisodeHealthItem::diagnosis, EpisodeHealthDiagnosis.class);
        int episodeCount = scanned.stream().mapToInt(h -> h.episodes().size()).sum();
        return new EpisodeHealthReport(overdueDays, scanned.size(), episodeCount,
                bucketCounts, diagnosisCounts,
                scanned.stream().map(this::toItem).toList());
    }

    /**
     * 逾期最久的排最前；没有播出日期的（算不出天数）排在最后。
     * <p>
     * 无日期的一档排最后而不是最前：它们的成因多是"未定档 / 尚未同步"，是这四档里
     * 最不确定、最不需要立刻动手的一批，让它们顶在第一屏会把真正的逾期缺集挤下去。
     * </p>
     */
    private Comparator<SubscriptionHealth> bySeverity() {
        return Comparator
                .comparing(SubscriptionHealth::maxOverdueDays,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(h -> StringUtils.defaultString(h.subscription().getTitle(), ""));
    }

    private <E extends Enum<E>> Map<String, Integer> countBy(List<SubscriptionHealth> scanned,
                                                             Function<EpisodeHealthItem, String> key,
                                                             Class<E> order) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        // 按枚举声明顺序预置 0 再累加：前端拿到的 key 顺序固定，图例不会因为
        // 某一档这次恰好为空就少一格、或者顺序整个抖动
        for (E value : order.getEnumConstants()) {
            counts.put(value.name(), 0);
        }
        for (SubscriptionHealth health : scanned) {
            for (EpisodeHealthItem item : health.episodes()) {
                counts.merge(key.apply(item), 1, Integer::sum);
            }
        }
        counts.values().removeIf(v -> v == 0);
        return counts;
    }

    private SubscriptionHealthItem toItem(SubscriptionHealth health) {
        PtSubscriptionPlus sub = health.subscription();
        Set<String> diagnoses = orderedNames(health, EpisodeHealthItem::diagnosis, EpisodeHealthDiagnosis.class);
        Set<String> buckets = orderedNames(health, EpisodeHealthItem::bucket, EpisodeHealthBucket.class);
        return new SubscriptionHealthItem(
                sub.getId(), sub.getTmdbId(), sub.getTitle(), sub.getPosterPath(),
                sub.getMediaType(), sub.getSeason(), autoSearchOn(sub),
                formatDateTime(sub.getLastSearchTime()),
                sub.getLastAutoSearchNoResult() == null ? 0 : sub.getLastAutoSearchNoResult(),
                rejectDetail(sub.getLastAutoSearchRejectSign()),
                health.maxOverdueDays(),
                List.copyOf(diagnoses), List.copyOf(buckets),
                health.episodes());
    }

    /** 去重并按枚举声明顺序排列，让前端不必自己维护一份排序表 */
    private <E extends Enum<E>> Set<String> orderedNames(SubscriptionHealth health,
                                                         Function<EpisodeHealthItem, String> key,
                                                         Class<E> order) {
        Set<String> present = health.episodes().stream().map(key).collect(Collectors.toSet());
        return Arrays.stream(order.getEnumConstants())
                .map(Enum::name)
                .filter(present::contains)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private EpisodeHealthItem toItem(PtSubscriptionEpisodePlus ep, PtSubscriptionPlus sub, LocalDate today) {
        LocalDate airDate = toLocalDate(ep.getAirDate());
        Integer overdue = airDate == null ? null : (int) ChronoUnit.DAYS.between(airDate, today);
        EpisodeHealthBucket bucket = bucketOf(ep, airDate);
        return new EpisodeHealthItem(ep.getEpisode(), ep.getState(),
                airDate == null ? null : airDate.toString(), overdue,
                bucket.name(), diagnose(ep, sub, bucket).name());
    }

    /**
     * 分档。判定顺序即优先级：
     * <ol>
     *   <li>{@code BLOCKED} 优先于一切——它是终态，自动链路已经放弃了这一集，
     *       无论有没有播出日期，用户要做的都是人工介入</li>
     *   <li>其次是「没有播出日期」——算不出逾期天数，就不能声称它逾期</li>
     *   <li>剩下的按状态分成缺失与在途两档</li>
     * </ol>
     */
    private EpisodeHealthBucket bucketOf(PtSubscriptionEpisodePlus ep, LocalDate airDate) {
        if (SubscriptionEpisodeState.BLOCKED.value().equals(ep.getState())) {
            return EpisodeHealthBucket.BLOCKED;
        }
        if (airDate == null) {
            return EpisodeHealthBucket.NO_AIR_DATE;
        }
        return SubscriptionEpisodeState.IN_FLIGHT.value().equals(ep.getState())
                ? EpisodeHealthBucket.OVERDUE_IN_FLIGHT
                : EpisodeHealthBucket.OVERDUE_MISSING;
    }

    /**
     * 诊断「为什么这一集还缺着」。
     * <p>
     * 在途的两种分得开靠 {@code file_confirmed}——与 {@code StuckEpisodeSweepService} 同一个字段、
     * 同一套语义：文件已确认在种子里时卡的是上传/刮削，重下解决不了问题，因此文案上明确
     * 不建议重下。
     * </p>
     */
    private EpisodeHealthDiagnosis diagnose(PtSubscriptionEpisodePlus ep, PtSubscriptionPlus sub,
                                            EpisodeHealthBucket bucket) {
        if (bucket == EpisodeHealthBucket.BLOCKED) {
            return EpisodeHealthDiagnosis.BLOCKED;
        }
        if (SubscriptionEpisodeState.IN_FLIGHT.value().equals(ep.getState())) {
            return DownloadTrackService.FILE_CONFIRMED.equals(ep.getFileConfirmed())
                    ? EpisodeHealthDiagnosis.UPLOAD_PENDING
                    : EpisodeHealthDiagnosis.DOWNLOADING;
        }
        // 以下都是 MISSING：先答"有没有人在管它"，再答"管了为什么没结果"
        if (!autoSearchOn(sub)) {
            return EpisodeHealthDiagnosis.AUTO_SEARCH_OFF;
        }
        int missStreak = sub.getLastAutoSearchNoResult() == null ? 0 : sub.getLastAutoSearchNoResult();
        if (missStreak <= 0) {
            return EpisodeHealthDiagnosis.SEARCHING;
        }
        String sign = sub.getLastAutoSearchRejectSign();
        // 指纹为空按「没搜到候选」处理：那正是 AutoSearchService 在一条淘汰日志都没有时的情形
        return StringUtils.isBlank(sign) || NO_CANDIDATE_SIGN.equals(sign)
                ? EpisodeHealthDiagnosis.SEARCH_NO_CANDIDATE
                : EpisodeHealthDiagnosis.SEARCH_ALL_REJECTED;
    }

    private boolean autoSearchOn(PtSubscriptionPlus sub) {
        return "1".equals(sub.getAutoSearch());
    }

    /** 把落空指纹（逗号分隔的 RejectCode 名）翻成中文标签串，给不出内容时返回 null */
    private String rejectDetail(String sign) {
        if (StringUtils.isBlank(sign) || NO_CANDIDATE_SIGN.equals(sign)) {
            return null;
        }
        String detail = Arrays.stream(sign.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(RejectCode::labelOf)
                .distinct()
                .collect(Collectors.joining("、"));
        return StringUtils.isBlank(detail) ? null : detail;
    }

    private static Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static String formatDateTime(Date date) {
        return date == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }
}
