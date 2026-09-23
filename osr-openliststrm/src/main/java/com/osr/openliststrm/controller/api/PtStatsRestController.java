package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.stats.PtStatsService;
import com.osr.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsFailReasonDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsRejectReasonDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsTrendPointDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * PT 统计仪表盘只读 REST API：6 个独立端点，不含业务逻辑，只做参数白名单校验 + 归属范围
 * 解析后转调 {@link PtStatsService}(设计文档4节：Controller 瘦、Service 厚)。
 * <p>
 * <b>归属隔离在这一层解析、在 Service 层落到 SQL 上</b>：订阅列表早就按 owner_user_id
 * 隔离，而统计面板此前是全站裸奔的——任何登录用户都能从 Top 活跃订阅里看到别人的剧名，
 * 点进去却被订阅页挡住。判据与 {@code PtSubscriptionRestController} 同一条：
 * 管理员看全站，其余用户看自己的 + 无归属的公共订阅。
 * </p>
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-stats")
public class PtStatsRestController extends BaseController {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);
    static final int DEFAULT_DAYS = 30;
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final PtStatsService statsService;

    public PtStatsRestController(PtStatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 不带 days 时统计全部历史（首页 PT 概览卡）；带了就按白名单归一，与其它端点同一口径。
     */
    @GetMapping("/overview")
    public Result<PtStatsOverviewDTO> overview(@RequestParam(value = "days", required = false) Integer days) {
        return Result.success(statsService.overview(days == null ? null : normalizeDays(days), scope()));
    }

    @GetMapping("/trend")
    public Result<List<PtStatsTrendPointDTO>> trend(@RequestParam(value = "days", required = false) Integer days) {
        return Result.success(statsService.trend(normalizeDays(days), scope()));
    }

    @GetMapping("/indexer-hit-rate")
    public Result<List<PtStatsIndexerHitRateDTO>> indexerHitRate() {
        return Result.success(statsService.indexerHitRate(scope()));
    }

    @GetMapping("/fail-reasons")
    public Result<List<PtStatsFailReasonDTO>> failReasons(@RequestParam(value = "days", required = false) Integer days) {
        return Result.success(statsService.failReasons(normalizeDays(days), scope()));
    }

    /**
     * 搜索淘汰原因分布。与 /fail-reasons 对称：那个统计"推送后下载失败"，
     * 这个统计"候选在推送前就被过滤规则挡掉"——后者才是"订阅一直补不到货"最常见的原因。
     */
    @GetMapping("/reject-reasons")
    public Result<List<PtStatsRejectReasonDTO>> rejectReasons() {
        return Result.success(statsService.rejectReasons(scope()));
    }

    @GetMapping("/top-subscriptions")
    public Result<List<PtStatsActiveSubscriptionDTO>> topSubscriptions(
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(statsService.topSubscriptions(normalizeDays(days), normalizeLimit(limit), scope()));
    }

    /**
     * 当前请求的可见范围。每个端点各调一次而不是缓存到字段上——
     * Controller 是单例，把请求级状态放实例字段上是本项目明令禁止的（见 ApiInterceptor 那次事故）。
     */
    private PtStatsScope scope() {
        return PtStatsScope.of(isAdmin(), getUserId());
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
