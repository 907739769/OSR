package com.osr.openliststrm.pt.task;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
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

    public AutoSearchService(IPtSubscriptionPlusService subscriptionService,
                             IPtFilterConfigPlusService filterConfigService,
                             SearchSupplementService searchSupplementService) {
        this.subscriptionService = subscriptionService;
        this.filterConfigService = filterConfigService;
        this.searchSupplementService = searchSupplementService;
    }

    /**
     * 扫一轮：对每个开启自动补搜、到期、且仍有缺集的订阅发起一次搜索。单个订阅异常不影响其他订阅。
     */
    public void run() {
        List<PtSubscriptionPlus> active = subscriptionService.listActive();
        if (active.isEmpty()) {
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
            notifySafely("🔍 订阅[" + StringUtils.escapeHtml(sub.getTitle()) + "] 自动补搜连续未找到可用资源，"
                    + "可等待 RSS 命中，或检查索引器配置（本提醒在下次搜到资源前只发一次）");
            sub.setLastAutoSearchNoResult(NO_RESULT);
            subscriptionService.updateById(sub);
        }
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

    private void notifySafely(String msg) {
        try {
            TgHelper.sendMsg(msg);
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }
}
