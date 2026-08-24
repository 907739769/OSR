package com.osr.openliststrm.pt.autoadd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.autoadd.dto.AutoAddRunResult;
import com.osr.openliststrm.pt.autoadd.source.PopularItem;
import com.osr.openliststrm.pt.autoadd.source.PopularSource;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 热门自动订阅：拉取榜单 → 补全 → 过滤 → 去重 → 建订阅，是各 {@link PopularSource} 与既有
 * {@link SubscriptionService#subscribe} 之间的粘合层，本身不关心数据来自 TMDb 还是别的源。
 * <p>
 * 「补全」那一步只对没有 tmdbId 的候选（豆瓣源）生效，见 {@link PopularItemResolver}。
 * 它<b>必须排在过滤之前</b>：过滤依据的三个字段全部来自 TMDb。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class AutoAddPopularService {

    private static final int DEFAULT_MAX_ADD_PER_RUN = 5;

    /** 与 pt_auto_add_log.message 的列宽一致 */
    private static final int MAX_LOG_MESSAGE_LENGTH = 500;

    @Autowired
    private List<PopularSource> sources;

    @Autowired
    private IPtAutoAddRulePlusService ruleService;

    @Autowired
    private IPtAutoAddLogPlusService logService;

    @Autowired
    private IPtSubscriptionPlusService subscriptionPlusService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private TmdbSearchService tmdbSearchService;

    @Autowired
    private PopularItemResolver resolver;

    /**
     * 执行单条规则。规则里 source 找不到对应数据源实现时直接跳过。
     */
    public AutoAddRunResult runRule(PtAutoAddRulePlus rule) {
        PopularSource source = sources.stream().filter(s -> s.supports(rule.getSource())).findFirst().orElse(null);
        if (source == null) {
            log.warn("热门自动订阅规则[{}] source={} 无对应数据源实现，跳过", rule.getId(), rule.getSource());
            return new AutoAddRunResult(0, 0, 0);
        }

        List<PopularItem> candidates;
        try {
            candidates = source.fetch(rule);
        } catch (Exception e) {
            log.error("热门自动订阅规则[{}]拉取榜单失败", rule.getId(), e);
            return new AutoAddRunResult(0, 0, 0);
        }

        Set<Integer> genreExclude = parseGenreExclude(rule.getGenreExclude());
        int maxAdd = (rule.getMaxAddPerRun() == null || rule.getMaxAddPerRun() <= 0)
                ? DEFAULT_MAX_ADD_PER_RUN : rule.getMaxAddPerRun();
        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(rule.getMediaType());

        int added = 0, skipped = 0, failed = 0;
        for (PopularItem item : candidates) {
            if (added >= maxAdd) {
                break;
            }
            // 豆瓣源拉回来的条目只有标题，先按标题搜 TMDb 补全 tmdbId 与过滤所需字段。
            // 补全必须在过滤之前：genreIds/voteAverage/voteCount 都来自 TMDb，不补的话
            // 规则上那三个过滤器对豆瓣源全部落空（恒为 null，按"不达标"处理会一条都放不过）。
            String sourceTitle = item.getTitle();
            if (StringUtils.isBlank(item.getTmdbId())) {
                String failReason = resolver.resolve(item, rule.getMediaType());
                if (failReason != null) {
                    writeLog(rule, item, null, "SKIPPED_NO_MATCH", failReason);
                    skipped++;
                    continue;
                }
            }
            // 标题被换成了 TMDb 侧的名字，把这次映射记进日志——用户核对"订的到底是不是那部"
            // 只能靠这一行，而误匹配是这条链路上唯一会造成实际损失的失败方式
            String matchNote = StringUtils.equals(sourceTitle, item.getTitle())
                    ? null : "来源标题《" + sourceTitle + "》匹配到 TMDb《" + item.getTitle() + "》";

            String skipReason = filterReason(item, genreExclude, rule);
            if (skipReason != null) {
                writeLog(rule, item, null, "SKIPPED_FILTER", join(skipReason, matchNote));
                skipped++;
                continue;
            }

            Integer season = movie ? null : resolveSeason(item.getTmdbId());
            if (alreadySubscribed(item.getTmdbId(), movie, season)) {
                writeLog(rule, item, season, "SKIPPED_EXISTS", join("同作品同季已存在订阅", matchNote));
                skipped++;
                continue;
            }

            SubscribeRequest request = new SubscribeRequest();
            request.setTmdbId(item.getTmdbId());
            request.setMediaType(movie ? SubscriptionService.TYPE_MOVIE : "TV");
            request.setSeason(season);
            request.setDownloaderId(rule.getDownloaderId());
            request.setFilterOverride(rule.getFilterOverride());
            try {
                subscriptionService.subscribe(request);
                writeLog(rule, item, season, "ADDED", matchNote);
                added++;
            } catch (Exception e) {
                log.warn("热门自动订阅规则[{}]建订阅失败 tmdbId={} title={}：{}",
                        rule.getId(), item.getTmdbId(), item.getTitle(), e.getMessage());
                writeLog(rule, item, season, "FAILED", join(e.getMessage(), matchNote));
                failed++;
            }
        }

        rule.setLastRunTime(new Date());
        ruleService.updateById(rule);
        log.info("热门自动订阅规则[{}]{} 执行完成：新增{} 跳过{} 失败{}",
                rule.getId(), rule.getName(), added, skipped, failed);
        return new AutoAddRunResult(added, skipped, failed);
    }

    /**
     * 到期的启用规则依次执行一轮。由 {@link AutoAddPopularTask} 定时调用。
     */
    public int runDueRules() {
        int ran = 0;
        for (PtAutoAddRulePlus rule : ruleService.listEnabled()) {
            if (!due(rule)) {
                continue;
            }
            try {
                runRule(rule);
                ran++;
            } catch (Exception e) {
                log.error("热门自动订阅规则[{}]执行异常", rule.getId(), e);
            }
        }
        return ran;
    }

    private boolean due(PtAutoAddRulePlus rule) {
        if (rule.getLastRunTime() == null) {
            return true;
        }
        int intervalHours = (rule.getIntervalHours() == null || rule.getIntervalHours() <= 0) ? 24 : rule.getIntervalHours();
        long elapsedMs = System.currentTimeMillis() - rule.getLastRunTime().getTime();
        return elapsedMs >= intervalHours * 3600_000L;
    }

    /**
     * 剧集查最新一季季号，取不到时兜底第 1 季；电影不涉及季，不调用本方法。
     */
    private Integer resolveSeason(String tmdbId) {
        try {
            return tmdbSearchService.getLatestSeasonNumber(tmdbId);
        } catch (Exception e) {
            log.warn("查询 tmdbId={} 最新季号失败，兜底订第1季：{}", tmdbId, e.getMessage());
            return 1;
        }
    }

    private boolean alreadySubscribed(String tmdbId, boolean movie, Integer season) {
        LambdaQueryWrapper<PtSubscriptionPlus> wrapper = new LambdaQueryWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getTmdbId, tmdbId)
                .eq(PtSubscriptionPlus::getMediaType, movie ? SubscriptionService.TYPE_MOVIE : "TV")
                .eq(PtSubscriptionPlus::getSeason, movie ? 0 : season);
        return subscriptionPlusService.count(wrapper) > 0;
    }

    /**
     * 返回跳过原因；不为空即命中过滤，null 表示通过全部过滤条件。
     */
    private String filterReason(PopularItem item, Set<Integer> genreExclude, PtAutoAddRulePlus rule) {
        if (!genreExclude.isEmpty() && item.getGenreIds() != null
                && item.getGenreIds().stream().anyMatch(genreExclude::contains)) {
            return "命中类型排除";
        }
        if (rule.getMinVoteAverage() != null
                && (item.getVoteAverage() == null || item.getVoteAverage() < rule.getMinVoteAverage())) {
            return "评分不达标";
        }
        if (rule.getMinVoteCount() != null
                && (item.getVoteCount() == null || item.getVoteCount() < rule.getMinVoteCount())) {
            return "评分人数不达标";
        }
        return null;
    }

    private Set<Integer> parseGenreExclude(String csv) {
        if (StringUtils.isBlank(csv)) {
            return new HashSet<>();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
    }

    /** 拼两段说明，任一为空时不留下多余的分隔符 */
    private String join(String first, String second) {
        if (StringUtils.isBlank(first)) {
            return second;
        }
        return StringUtils.isBlank(second) ? first : first + "；" + second;
    }

    private void writeLog(PtAutoAddRulePlus rule, PopularItem item, Integer season, String result, String message) {
        PtAutoAddLogPlus entry = new PtAutoAddLogPlus();
        entry.setRuleId(rule.getId());
        entry.setTmdbId(item.getTmdbId());
        entry.setSourceItemId(item.getDoubanId());
        entry.setSourceItemUrl(item.getSourceUrl());
        entry.setMediaType(item.getMediaType());
        entry.setTitle(item.getTitle());
        entry.setSeason(season);
        entry.setResult(result);
        // message 列是 varchar(500)，异常消息可以任意长（下载器/索引器的错误常带一整个响应体），
        // 不截断的话整条日志写不进去，而这条日志正是用来解释"这轮为什么没加"的
        entry.setMessage(StringUtils.substring(message, 0, MAX_LOG_MESSAGE_LENGTH));
        try {
            logService.save(entry);
        } catch (Exception e) {
            log.warn("写入热门自动订阅日志失败：{}", e.getMessage());
        }
    }
}
