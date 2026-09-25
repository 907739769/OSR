package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.pt.stats.SeedingStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 保种与上传量看板（统计仪表盘里的一块）。
 * <p>
 * <b>仅管理员</b>：数据来自下载器本身，一台下载器里是全家所有人的种子，没有按归属拆分的口径，
 * 而 PT 统计的其余端点都做了归属隔离——这一块只能整体对管理员开放。
 * </p>
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-stats/seeding")
public class PtSeedingRestController extends BaseController {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);

    private final SeedingStatsService seedingStatsService;

    public PtSeedingRestController(SeedingStatsService seedingStatsService) {
        this.seedingStatsService = seedingStatsService;
    }

    /** days 与统计仪表盘其余端点同一套挡位，非法值回退 30 */
    @GetMapping
    public Result<SeedingStatsService.Overview> overview(@RequestParam(value = "days", required = false) Integer days) {
        Result<SeedingStatsService.Overview> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        return Result.success(seedingStatsService.overview(days != null && ALLOWED_DAYS.contains(days) ? days : 30));
    }
}
