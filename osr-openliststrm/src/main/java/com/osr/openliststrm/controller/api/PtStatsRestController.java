package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.openliststrm.pt.stats.PtStatsService;
import com.osr.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsFailReasonDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsTrendPointDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * PT 统计仪表盘只读 REST API：5 个独立端点，不含业务逻辑，只做参数白名单校验后转调
 * {@link PtStatsService}(设计文档4节：Controller 瘦、Service 厚)。
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-stats")
public class PtStatsRestController {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);
    static final int DEFAULT_DAYS = 30;
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final PtStatsService statsService;

    public PtStatsRestController(PtStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/overview")
    public Result<PtStatsOverviewDTO> overview() {
        return Result.success(statsService.overview());
    }

    @GetMapping("/trend")
    public Result<List<PtStatsTrendPointDTO>> trend(@RequestParam(value = "days", required = false) Integer days) {
        return Result.success(statsService.trend(normalizeDays(days)));
    }

    @GetMapping("/indexer-hit-rate")
    public Result<List<PtStatsIndexerHitRateDTO>> indexerHitRate() {
        return Result.success(statsService.indexerHitRate());
    }

    @GetMapping("/fail-reasons")
    public Result<List<PtStatsFailReasonDTO>> failReasons(@RequestParam(value = "days", required = false) Integer days) {
        return Result.success(statsService.failReasons(normalizeDays(days)));
    }

    @GetMapping("/top-subscriptions")
    public Result<List<PtStatsActiveSubscriptionDTO>> topSubscriptions(
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(statsService.topSubscriptions(normalizeDays(days), normalizeLimit(limit)));
    }

    /** days 只允许 7/30/90，非法值(含null)一律回退到 30，避免前端传入超大天数触发无边界的全表扫描 */
    static int normalizeDays(Integer days) {
        return days != null && ALLOWED_DAYS.contains(days) ? days : DEFAULT_DAYS;
    }

    /** limit 上限 50，避免一次拉出过多订阅；非法值(null/<=0)回退默认 10 */
    static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
