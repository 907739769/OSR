package com.ruoyi.openliststrm.controller.api;

import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.dashboard.stats.DashboardStatsService;
import com.ruoyi.openliststrm.dashboard.stats.dto.DashboardTrendPointDTO;
import com.ruoyi.openliststrm.enums.CopyStatusEnum;
import com.ruoyi.openliststrm.enums.StrmStatusEnum;
import com.ruoyi.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仪表盘统计 REST API控制器
 */
@RestController
@RequestMapping("/api/openliststrm/dashboard")
public class OpenlistDashboardRestController {

    private static final Logger log = LoggerFactory.getLogger(OpenlistDashboardRestController.class);

    @Autowired
    private IOpenlistCopyPlusService openlistCopyPlusService;

    @Autowired
    private IOpenlistStrmPlusService openlistStrmPlusService;

    @Autowired
    private IRenameDetailPlusService renameDetailPlusService;

    @Autowired
    private DashboardStatsService dashboardStatsService;

    private static final Set<String> ALLOWED_TREND_TYPES = Set.of("copy", "strm", "rename");
    private static final Set<Integer> ALLOWED_TREND_DAYS = Set.of(7, 14, 30);

    /**
     * 获取仪表盘统计数据。
     * <p>
     * copy/strm 各按状态 GROUP BY 一次查询即可拿到总数与各状态数，相比原先每个状态各一条
     * COUNT(*)（共8条查询），压缩为 3 条（copy分组、strm分组、rename总数）。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            Map<String, Long> copyByStatus = countGroupByStatus(
                    Wrappers.<OpenlistCopyPlus>query().select("copy_status as status, count(*) as count").groupBy("copy_status"),
                    openlistCopyPlusService);
            Map<String, Long> strmByStatus = countGroupByStatus(
                    Wrappers.<OpenlistStrmPlus>query().select("strm_status as status, count(*) as count").groupBy("strm_status"),
                    openlistStrmPlusService);
            long renameTotal = renameDetailPlusService.count();

            long copyTotal = copyByStatus.values().stream().mapToLong(Long::longValue).sum();
            long strmTotal = strmByStatus.values().stream().mapToLong(Long::longValue).sum();
            long copySuccess = copyByStatus.getOrDefault(CopyStatusEnum.SUCCESS.getCode(), 0L);
            long copyFailed = copyByStatus.getOrDefault(CopyStatusEnum.FAILED.getCode(), 0L);
            long copyProcessing = copyByStatus.getOrDefault(CopyStatusEnum.PROCESSING.getCode(), 0L);
            long strmSuccess = strmByStatus.getOrDefault(StrmStatusEnum.SUCCESS.getCode(), 0L);
            long strmFailed = strmByStatus.getOrDefault(StrmStatusEnum.FAILED.getCode(), 0L);

            long totalDone = copyTotal + strmTotal;
            long totalSuccess = copySuccess + strmSuccess;
            double successRate = totalDone > 0 ? Math.round(totalSuccess * 1000.0 / totalDone) / 10.0 : 0.0;

            stats.put("strmRecordCount", strmTotal);
            stats.put("copyRecordCount", copyTotal);
            stats.put("renameDetailCount", renameTotal);
            stats.put("successRate", successRate);
            stats.put("failedCount", copyFailed + strmFailed);
            stats.put("processingCount", copyProcessing);
        } catch (Exception e) {
            log.error("获取仪表盘统计失败", e);
        }
        return Result.success(stats);
    }

    /** 执行 GROUP BY status 查询并转为 status -> count 的映射 */
    private <T> Map<String, Long> countGroupByStatus(QueryWrapper<T> wrapper, IService<T> service) {
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : service.listMaps(wrapper)) {
            result.put(String.valueOf(row.get("status")), Long.parseLong(row.get("count").toString()));
        }
        return result;
    }

    @PostMapping("/copy/stats")
    public Result<Map<String, Long>> copyStats(@RequestParam(value = "range", defaultValue = "today") String range) {
        Map<String, Long> result = getStatsByStatus("copy", range);
        return Result.success(result);
    }

    @PostMapping("/strm/stats")
    public Result<Map<String, Long>> strmStats(@RequestParam(value = "range", defaultValue = "today") String range) {
        Map<String, Long> result = getStatsByStatus("strm", range);
        return Result.success(result);
    }

    @PostMapping("/renameDetail/stats")
    public Result<Map<String, Long>> renameStats(@RequestParam(value = "range", defaultValue = "today") String range) {
        Map<String, Long> result = getStatsByStatus("rename", range);
        return Result.success(result);
    }

    /**
     * COPY/STRM/Rename 按天分组的趋势数据，取代原来只有当日/昨日/全部快照的饼图。
     * type 只允许 copy/strm/rename，days 只允许 7/14/30，非法值一律回退默认，避免越权触发大范围扫描。
     */
    @GetMapping("/trend")
    public Result<List<DashboardTrendPointDTO>> trend(@RequestParam("type") String type,
                                                        @RequestParam(value = "days", required = false) Integer days) {
        String safeType = ALLOWED_TREND_TYPES.contains(type) ? type : "strm";
        int safeDays = days != null && ALLOWED_TREND_DAYS.contains(days) ? days : 7;
        return Result.success(dashboardStatsService.trend(safeType, safeDays));
    }

    private Map<String, Long> getStatsByStatus(String type, String range) {
        LocalDate today = LocalDate.now();
        Map<String, Long> result = new LinkedHashMap<>();

        if ("copy".equals(type)) {
            var wrapper = Wrappers.<OpenlistCopyPlus>query();
            wrapper.select("copy_status as status, count(*) as count")
                    .between(StringUtils.isEmpty(range) || "today".equals(range), "create_time", today.atStartOfDay(), today.plusDays(1).atStartOfDay())
                    .between("yesterday".equals(range), "create_time", today.minusDays(1).atStartOfDay(), today.atStartOfDay())
                    .groupBy("copy_status");
            List<Map<String, Object>> maps = openlistCopyPlusService.listMaps(wrapper);
            for (Map<String, Object> map : maps) {
                String status = String.valueOf(map.get("status"));
                Long count = Long.parseLong(map.get("count").toString());
                result.put(CopyStatusEnum.getDescByCode(status), count);
            }
        } else if ("strm".equals(type)) {
            var wrapper = Wrappers.<OpenlistStrmPlus>query();
            wrapper.select("strm_status as status, count(*) as count")
                    .between(StringUtils.isEmpty(range) || "today".equals(range), "create_time", today.atStartOfDay(), today.plusDays(1).atStartOfDay())
                    .between("yesterday".equals(range), "create_time", today.minusDays(1).atStartOfDay(), today.atStartOfDay())
                    .groupBy("strm_status");
            List<Map<String, Object>> maps = openlistStrmPlusService.listMaps(wrapper);
            for (Map<String, Object> map : maps) {
                String status = String.valueOf(map.get("status"));
                Long count = Long.parseLong(map.get("count").toString());
                result.put(StrmStatusEnum.getDescByCode(status), count);
            }
        } else if ("rename".equals(type)) {
            var wrapper = Wrappers.<RenameDetailPlus>query();
            wrapper.select("status as status, count(*) as count")
                    .between(StringUtils.isEmpty(range) || "today".equals(range), "create_time", today.atStartOfDay(), today.plusDays(1).atStartOfDay())
                    .between("yesterday".equals(range), "create_time", today.minusDays(1).atStartOfDay(), today.atStartOfDay())
                    .groupBy("status");
            List<Map<String, Object>> maps = renameDetailPlusService.listMaps(wrapper);
            for (Map<String, Object> map : maps) {
                String status = String.valueOf(map.get("status"));
                Long count = Long.parseLong(map.get("count").toString());
                result.put(StrmStatusEnum.getDescByCode(status), count);
            }
        }
        return result;
    }
}
