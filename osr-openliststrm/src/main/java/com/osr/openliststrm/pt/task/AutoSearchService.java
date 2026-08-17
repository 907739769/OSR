package com.osr.openliststrm.pt.task;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyTarget;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 自动补搜业务逻辑：对开启了 auto_search 且到期的订阅，发起一次搜索，
 * 季包优先、未命中则从同一候选池本地逐集匹配补散集（见 {@link SearchSupplementService#searchAndPushMissing}），
 * 每个到期订阅每轮仍只发一次搜索请求，不会因为支持散集而增加对索引器的请求量。
 *
 * @author Jack
 */
@Slf4j
@Service
public class AutoSearchService {

    private static final String AUTO_SEARCH_ON = "1";
    private static final String NO_RESULT = "1";
    private static final String HAS_RESULT = "0";
    private static final int DEFAULT_INTERVAL_HOURS = 24;

    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtFilterConfigPlusService filterConfigService;
    private final SearchSupplementService searchSupplementService;
    private final IPtIndexerPlusService indexerService;

    public AutoSearchService(IPtSubscriptionPlusService subscriptionService,
                             IPtFilterConfigPlusService filterConfigService,
                             SearchSupplementService searchSupplementService,
                             IPtIndexerPlusService indexerService) {
        this.subscriptionService = subscriptionService;
        this.filterConfigService = filterConfigService;
        this.searchSupplementService = searchSupplementService;
        this.indexerService = indexerService;
    }

    /**
     * 扫一轮：对每个开启自动补搜、到期、且仍有缺集的订阅发起一次搜索。单个订阅异常不影响其他订阅。
     */
    public void run() {
        List<PtSubscriptionPlus> active = subscriptionService.listActive();
        if (active.isEmpty()) {
            return;
        }
        // 一个启用中的索引器都没有时整轮直接跳过并说明原因。否则每个订阅都会走完一遍
        // 搜索流程、各自得到「0 个候选」，日志里看起来像是「站上都没资源」，
        // 而实际上一个请求都没发出去过——用户会照着去改过滤规则和关键词
        if (indexerService.listEnabled().isEmpty()) {
            log.warn("没有启用中的索引器，本轮自动补搜跳过（共 {} 个活跃订阅待搜）", active.size());
            return;
        }
        int intervalHours = resolveIntervalHours();
        long now = System.currentTimeMillis();

        for (PtSubscriptionPlus sub : active) {
            if (!AUTO_SEARCH_ON.equals(sub.getAutoSearch())) {
                continue;
            }
            if (!isDue(sub, intervalHours, now)) {
                continue;
            }
            try {
                trySearch(sub);
            } catch (Exception e) {
                log.warn("订阅[{}]自动补搜失败：{}", sub.getId(), e.getMessage());
            }
        }
    }

    /**
     * 是否落空由 {@link SearchAndPushSummary#isSkipped()}（无缺集/订阅不可搜）以及
     * {@link SearchAndPushSummary#anyPushed()} 共同决定；跳过的情况不触碰通知去重标记，
     * 因为跳过既不是"落空"也不是"命中"，不该覆盖上一次真正搜索留下的状态。
     * 通知只在状态从"上次落空"变为"本次落空"之外的边界触发一次，避免长期缺集的老剧
     * 每轮（默认24小时）都收到一条"未搜到资源"的 TG 通知。
     */
    private void trySearch(PtSubscriptionPlus sub) {
        SearchAndPushSummary summary = searchSupplementService.searchAndPushMissing(sub.getId());
        if (summary.isSkipped()) {
            return;
        }
        boolean previouslyNoResult = NO_RESULT.equals(sub.getLastAutoSearchNoResult());
        if (summary.anyPushed()) {
            if (previouslyNoResult) {
                sub.setLastAutoSearchNoResult(HAS_RESULT);
                subscriptionService.updateById(sub);
            }
            return;
        }
        if (!previouslyNoResult) {
            notifySafely(describeNoResult(sub, summary.getRejectSummary()), sub);
            sub.setLastAutoSearchNoResult(NO_RESULT);
            subscriptionService.updateById(sub);
        }
    }

    /**
     * 落空通知的文案：能说出「被自己的过滤规则淘汰了多少」就直说，别再一律提示检查索引器。
     * <p>
     * 这两种落空是完全不同的处置方向：候选被规则淘汰要去调过滤配置，压根没搜到候选才该看
     * 关键词与索引器。旧文案不加区分地写"检查索引器配置"，在前一种情况下把用户引向了
     * 一个根本没问题的地方——索引器好好地返回了上百个候选，是 freeOnly 或分辨率白名单全清了。
     * </p>
     */
    private String describeNoResult(PtSubscriptionPlus sub, String rejectSummary) {
        String title = StringUtils.escapeHtml(sub.getTitle());
        if (StringUtils.isNotBlank(rejectSummary)) {
            return "🔍 订阅[" + title + "] 自动补搜未推送任何资源——" + StringUtils.escapeHtml(rejectSummary)
                    + "。请检查过滤规则是否过严（本提醒在下次推送成功前只发一次）";
        }
        return "🔍 订阅[" + title + "] 自动补搜连续未找到可用资源，"
                + "可等待 RSS 命中，或检查关键词与索引器配置（本提醒在下次搜到资源前只发一次）";
    }

    private boolean isDue(PtSubscriptionPlus sub, int intervalHours, long now) {
        if (sub.getLastSearchTime() == null) {
            return true;
        }
        return now - sub.getLastSearchTime().getTime() >= intervalHours * 3600_000L;
    }

    private int resolveIntervalHours() {
        PtFilterConfigPlus config = filterConfigService.getConfig();
        Integer hours = config.getAutoSearchIntervalHours();
        return hours == null ? DEFAULT_INTERVAL_HOURS : hours;
    }

    private void notifySafely(String msg, PtSubscriptionPlus sub) {
        try {
            // SUBSCRIPTION_SEARCH 而不是 GENERAL：GENERAL 是索引器故障、复制超时那类系统告警，
            // 补搜落空是某条订阅自己的事，处置方向也不同（去调过滤规则或关键词）
            TgHelper.sendMsg(NotificationType.SUBSCRIPTION_SEARCH, msg,
                    NotifyTarget.owner(sub == null ? null : sub.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }
}
