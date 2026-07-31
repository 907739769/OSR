package com.ruoyi.openliststrm.dashboard.stats;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ruoyi.openliststrm.dashboard.stats.dto.DashboardTrendPointDTO;
import com.ruoyi.openliststrm.enums.CopyStatusEnum;
import com.ruoyi.openliststrm.enums.StrmStatusEnum;
import com.ruoyi.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 首页仪表盘 COPY/STRM/Rename 三类任务的按天趋势统计：照抄 PtStatsService.trend() 的模式，
 * 全部用 QueryWrapper 原生 select/groupBy + IService.listMaps 完成分组统计，不新建 XML Mapper。
 *
 * @author Jack
 */
@Service
public class DashboardStatsService {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final IOpenlistCopyPlusService openlistCopyPlusService;
    private final IOpenlistStrmPlusService openlistStrmPlusService;
    private final IRenameDetailPlusService renameDetailPlusService;

    public DashboardStatsService(IOpenlistCopyPlusService openlistCopyPlusService,
                                  IOpenlistStrmPlusService openlistStrmPlusService,
                                  IRenameDetailPlusService renameDetailPlusService) {
        this.openlistCopyPlusService = openlistCopyPlusService;
        this.openlistStrmPlusService = openlistStrmPlusService;
        this.renameDetailPlusService = renameDetailPlusService;
    }

    /**
     * 按天分组的趋势：type 为 copy/strm/rename，days 为回溯天数（含今天）。
     */
    public List<DashboardTrendPointDTO> trend(String type, int days) {
        LocalDate start = LocalDate.now().minusDays(days - 1L);

        List<Map<String, Object>> rows;
        String successCode;
        String failedCode;
        switch (type) {
            case "copy":
                successCode = CopyStatusEnum.SUCCESS.getCode();
                failedCode = CopyStatusEnum.FAILED.getCode();
                rows = openlistCopyPlusService.listMaps(dayGroupWrapper(
                        Wrappers.<OpenlistCopyPlus>query(), "copy_status", successCode, failedCode, start));
                break;
            case "strm":
                successCode = StrmStatusEnum.SUCCESS.getCode();
                failedCode = StrmStatusEnum.FAILED.getCode();
                rows = openlistStrmPlusService.listMaps(dayGroupWrapper(
                        Wrappers.<OpenlistStrmPlus>query(), "strm_status", successCode, failedCode, start));
                break;
            case "rename":
                successCode = StrmStatusEnum.SUCCESS.getCode();
                failedCode = StrmStatusEnum.FAILED.getCode();
                rows = renameDetailPlusService.listMaps(dayGroupWrapper(
                        Wrappers.<RenameDetailPlus>query(), "status", successCode, failedCode, start));
                break;
            default:
                rows = List.of();
        }

        Map<String, Map<String, Object>> byDay = rows.stream()
                .collect(Collectors.toMap(r -> String.valueOf(r.get("day")), r -> r));

        List<DashboardTrendPointDTO> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate day = start.plusDays(i);
            String key = day.format(DAY_FORMATTER);
            Map<String, Object> row = byDay.get(key);
            DashboardTrendPointDTO point = new DashboardTrendPointDTO();
            point.setDate(key);
            if (row != null) {
                point.setTotalCount(asLong(row.get("total_count")));
                point.setSuccessCount(asLong(row.get("success_count")));
                point.setFailedCount(asLong(row.get("failed_count")));
            }
            result.add(point);
        }
        return result;
    }

    /** 按 create_time 所在日期分组，聚合总数/成功数/失败数 */
    private <T> QueryWrapper<T> dayGroupWrapper(QueryWrapper<T> wrapper, String statusColumn,
                                                 String successCode, String failedCode, LocalDate start) {
        wrapper.select("DATE_FORMAT(create_time,'%Y-%m-%d') as day, "
                        + "count(*) as total_count, "
                        + "SUM(CASE WHEN " + statusColumn + "='" + successCode + "' THEN 1 ELSE 0 END) as success_count, "
                        + "SUM(CASE WHEN " + statusColumn + "='" + failedCode + "' THEN 1 ELSE 0 END) as failed_count")
                .ge("create_time", start.atStartOfDay())
                .groupBy("DATE_FORMAT(create_time,'%Y-%m-%d')");
        return wrapper;
    }

    private static long asLong(Object v) {
        return v == null ? 0L : Long.parseLong(v.toString());
    }
}
