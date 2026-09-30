package com.osr.openliststrm.pt.health;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.PtLogText;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 缺集体检自动开关补搜（sys_config {@code openlist.pt.health.autosearch}，默认关）。
 * <p>
 * <b>开</b>：体检扫到有「逾期缺失」集、却没开自动补搜的订阅，替用户打开。
 * 自动补搜默认关是为了不让追完的老剧空转（见本目录 AGENTS.md），而体检报出来的这批恰恰是
 * 「确实缺着、却没人在搜」的——此前只能靠用户看到提醒后逐条去点。
 * </p>
 * <p>
 * <b>关</b>：只关<b>自己打开的</b>那些（{@code health_auto_search_time} 非空），条件是订阅已不在
 * 订阅中，或者当前既没有已播出的缺失集、也没有在途集，且开启与最近一次命中都已过去
 * {@link #IDLE_DAYS} 天。只看「当前不缺」就关的话，追更中的剧每周新播一集、缺一两天、补上、关掉、
 * 三天后又逾期再打开，开关一周来回好几次；用最近命中时间兜着，在更的剧会一直开着，
 * 真正追完的老剧一周后自然关掉——这正是默认关想避免的那种空转。
 * 用户手动动过开关的订阅那一列已被清空，不在这里的管辖范围内。
 * </p>
 * <p>
 * 这里<b>只写订阅级的开关</b>，不碰集状态、不发起搜索：打开之后由 {@code AutoSearchTask}
 * （30 分钟心跳）按正常节奏接手。体检是只读诊断层这条约束的例外只有这一处，且与页面上
 * 「开启自动补搜」按钮做的是同一件事。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class HealthAutoSearchService {

    /** 体检打开的补搜，补齐后空闲多少天关掉 */
    static final int IDLE_DAYS = 7;

    private final EpisodeHealthService healthService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final OpenlistConfig openlistConfig;

    public HealthAutoSearchService(EpisodeHealthService healthService,
                                   IPtSubscriptionPlusService subscriptionService,
                                   IPtSubscriptionEpisodePlusService episodeService,
                                   OpenlistConfig openlistConfig) {
        this.healthService = healthService;
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.openlistConfig = openlistConfig;
    }

    /**
     * 本轮开/关的结果。{@code enabled} 给逾期提醒用：刚替用户打开的要在消息里说一声，
     * 否则用户看到「原因：未开启自动补搜」再去点，会发现已经开了。
     */
    public record Outcome(List<PtSubscriptionPlus> enabled, List<PtSubscriptionPlus> disabled) {

        static final Outcome NONE = new Outcome(List.of(), List.of());

        public boolean changed() {
            return !enabled.isEmpty() || !disabled.isEmpty();
        }
    }

    /**
     * 跑一轮。开关关着时<b>仍然</b>执行「关掉」那一半：用户关掉这项功能后，
     * 此前被它打开的订阅补齐了照样该关，不然它们会作为「谁开的都不知道」的开关一直留着。
     */
    public Outcome apply() {
        LocalDate today = LocalDate.now();
        List<PtSubscriptionPlus> disabled = disableIdle(today);
        List<PtSubscriptionPlus> enabled = openlistConfig.isHealthAutoSearchEnabled()
                ? enableOverdue(healthService.scan(today))
                : List.of();
        Outcome outcome = new Outcome(enabled, disabled);
        if (outcome.changed()) {
            log.info("缺集体检自动补搜：开启 {} 部 {}，关闭 {} 部 {}",
                    enabled.size(), titles(enabled), disabled.size(), titles(disabled));
        }
        return outcome;
    }

    /** 有逾期缺失、未开补搜、未被忽略的订阅中订阅，打开补搜 */
    List<PtSubscriptionPlus> enableOverdue(List<SubscriptionHealth> scanned) {
        List<PtSubscriptionPlus> targets = new ArrayList<>();
        for (SubscriptionHealth health : scanned) {
            PtSubscriptionPlus sub = health.subscription();
            if (EpisodeHealthService.isIgnored(sub)
                    || "1".equals(sub.getAutoSearch())
                    || !SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())
                    || health.episodesIn(EpisodeHealthBucket.OVERDUE_MISSING).isEmpty()) {
                continue;
            }
            targets.add(sub);
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        int changed = subscriptionService.enableAutoSearchByHealth(
                targets.stream().map(PtSubscriptionPlus::getId).toList(), new Date());
        if (changed < targets.size()) {
            // 扫描与更新之间用户手动开过几条，条件更新跳过了它们；消息里少列几部无伤大雅
            log.debug("缺集体检自动开启补搜：计划 {} 部，实际 {} 部", targets.size(), changed);
        }
        return targets;
    }

    /** 关掉体检自己打开、且已经空闲下来的补搜 */
    List<PtSubscriptionPlus> disableIdle(LocalDate today) {
        List<PtSubscriptionPlus> disabled = new ArrayList<>();
        for (PtSubscriptionPlus sub : subscriptionService.listHealthAutoSearched()) {
            try {
                if (shouldDisable(sub, episodeService.listBySubscription(sub.getId()), today)
                        && subscriptionService.disableHealthAutoSearch(sub.getId())) {
                    disabled.add(sub);
                }
            } catch (Exception e) {
                log.warn("{} 判断是否关闭体检开启的补搜失败：{}", PtLogText.subject(sub), e.getMessage(), e);
            }
        }
        return disabled;
    }

    /**
     * 该不该关。订阅不在订阅中（完结、暂停）直接关——补搜本来就只跑订阅中的，留着开关只是
     * 恢复订阅那一刻会冒出一个谁开的都不知道的补搜。
     */
    static boolean shouldDisable(PtSubscriptionPlus sub, List<PtSubscriptionEpisodePlus> episodes, LocalDate today) {
        if (!SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            return true;
        }
        boolean busy = episodes.stream().anyMatch(ep ->
                SubscriptionService.STATE_IN_FLIGHT.equals(ep.getState())
                        || (SubscriptionService.STATE_MISSING.equals(ep.getState()) && SubscriptionService.aired(ep, today)));
        if (busy) {
            return false;
        }
        LocalDate lastActive = latest(toLocalDate(sub.getHealthAutoSearchTime()), toLocalDate(sub.getLastMatchTime()));
        return lastActive == null || !lastActive.plusDays(IDLE_DAYS).isAfter(today);
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static List<String> titles(List<PtSubscriptionPlus> subs) {
        return subs.stream().map(PtSubscriptionPlus::getTitle).toList();
    }
}
