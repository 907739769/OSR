package com.osr.openliststrm.pt.stats;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsFailReasonDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsRejectReasonDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsTrendPointDTO;
import com.osr.openliststrm.pt.filter.RejectCode;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.task.DownloadRecordState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * PT 订阅统计仪表盘的聚合查询：全部用 QueryWrapper 原生 select/groupBy + IService.listMaps
 * 完成分组统计，不新建 XML Mapper，见设计文档 2.2 节。
 *
 * @author Jack
 */
@Service
public class PtStatsService {

    private static final String STATE_COMPLETED = DownloadRecordState.COMPLETED.value();
    private static final String STATE_FAILED = DownloadRecordState.FAILED.value();
    private static final java.time.format.DateTimeFormatter DAY_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final IPtDownloadRecordPlusService downloadRecordService;
    private final IPtSearchLogPlusService searchLogService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtIndexerPlusService indexerService;

    public PtStatsService(IPtDownloadRecordPlusService downloadRecordService,
                           IPtSearchLogPlusService searchLogService,
                           IPtSubscriptionPlusService subscriptionService,
                           IPtIndexerPlusService indexerService) {
        this.downloadRecordService = downloadRecordService;
        this.searchLogService = searchLogService;
        this.subscriptionService = subscriptionService;
        this.indexerService = indexerService;
    }

    /**
     * 总览统计：订阅总数/活跃数 + 下载记录一次性聚合(总数/完成/失败/成功率/全局平均耗时)，
     * 不做时间范围筛选(设计文档2.1，overview 覆盖全量历史)。
     */
    public PtStatsOverviewDTO overview() {
        PtStatsOverviewDTO dto = new PtStatsOverviewDTO();
        dto.setTotalSubscriptions(subscriptionService.count());
        dto.setActiveSubscriptions(subscriptionService.count(
                Wrappers.<PtSubscriptionPlus>query().eq("status", SubscriptionService.STATUS_ACTIVE)));

        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query().select(
                        "count(*) as total, "
                                + "SUM(CASE WHEN state='" + STATE_COMPLETED + "' THEN 1 ELSE 0 END) as completed_count, "
                                + "SUM(CASE WHEN state='" + STATE_FAILED + "' THEN 1 ELSE 0 END) as failed_count, "
                                + "AVG(CASE WHEN state='" + STATE_COMPLETED
                                + "' THEN TIMESTAMPDIFF(MINUTE, pushed_time, completed_time) ELSE NULL END) as avg_duration_minutes"));
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);

        long total = asLong(row.get("total"));
        long completed = asLong(row.get("completed_count"));
        long failed = asLong(row.get("failed_count"));
        dto.setTotalDownloadRecords(total);
        dto.setCompletedCount(completed);
        dto.setFailedCount(failed);
        dto.setSuccessRate(total > 0 ? Math.round(completed * 1000.0 / total) / 10.0 : 0.0);
        Double avg = asDouble(row.get("avg_duration_minutes"));
        dto.setAvgDurationMinutes(avg == null ? 0.0 : avg);
        return dto;
    }

    /**
     * 下载量趋势：按 pushed_time 所在日期分组，日期区间连续补齐(设计文档2.1只用 pushed_time/state
     * 两个字段——按"推送日期"这一维度分类，而不是按完成/失败发生的日期分类)。
     */
    public List<PtStatsTrendPointDTO> trend(int days) {
        java.time.LocalDate start = java.time.LocalDate.now().minusDays(days - 1L);

        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query()
                        .select("DATE_FORMAT(pushed_time,'%Y-%m-%d') as day, "
                                + "count(*) as pushed_count, "
                                + "SUM(CASE WHEN state='" + STATE_COMPLETED + "' THEN 1 ELSE 0 END) as completed_count, "
                                + "SUM(CASE WHEN state='" + STATE_FAILED + "' THEN 1 ELSE 0 END) as failed_count, "
                                + "AVG(CASE WHEN state='" + STATE_COMPLETED
                                + "' THEN TIMESTAMPDIFF(MINUTE, pushed_time, completed_time) ELSE NULL END) as avg_duration_minutes")
                        .ge("pushed_time", start.atStartOfDay())
                        .groupBy("DATE_FORMAT(pushed_time,'%Y-%m-%d')"));

        Map<String, Map<String, Object>> byDay = rows.stream()
                .collect(java.util.stream.Collectors.toMap(r -> String.valueOf(r.get("day")), r -> r));

        List<PtStatsTrendPointDTO> result = new java.util.ArrayList<>();
        for (int i = 0; i < days; i++) {
            java.time.LocalDate day = start.plusDays(i);
            String key = day.format(DAY_FORMATTER);
            Map<String, Object> row = byDay.get(key);
            PtStatsTrendPointDTO point = new PtStatsTrendPointDTO();
            point.setDate(key);
            if (row == null) {
                point.setPushedCount(0);
                point.setCompletedCount(0);
                point.setFailedCount(0);
                point.setAvgDurationMinutes(null);
            } else {
                point.setPushedCount(asLong(row.get("pushed_count")));
                point.setCompletedCount(asLong(row.get("completed_count")));
                point.setFailedCount(asLong(row.get("failed_count")));
                point.setAvgDurationMinutes(asDouble(row.get("avg_duration_minutes")));
            }
            result.add(point);
        }
        return result;
    }

    /**
     * 索引器命中率：驱动集合是 pt_indexer 全量(indexerService.list())，不是只查有日志的索引器，
     * 新增索引器还没跑过时 hasData=false 也要出现在结果里(设计文档测试计划)。不做 days 筛选，
     * 见设计文档 2.4 节：pt_search_log 本身按订阅保留≤200条，再叠加时间筛选口径会不一致。
     */
    public List<PtStatsIndexerHitRateDTO> indexerHitRate() {
        List<Map<String, Object>> rows = searchLogService.listMaps(
                Wrappers.<PtSearchLogPlus>query()
                        .select("indexer_id as indexer_id, "
                                + "SUM(CASE WHEN accepted='1' THEN 1 ELSE 0 END) as accepted_count, "
                                + "SUM(CASE WHEN accepted='0' THEN 1 ELSE 0 END) as rejected_count")
                        .isNotNull("indexer_id")
                        .groupBy("indexer_id"));

        Map<Integer, Map<String, Object>> byIndexer = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> ((Number) r.get("indexer_id")).intValue(), r -> r));

        List<PtIndexerPlus> indexers = indexerService.list();
        List<PtStatsIndexerHitRateDTO> result = new java.util.ArrayList<>();
        for (PtIndexerPlus indexer : indexers) {
            Map<String, Object> row = byIndexer.get(indexer.getId());
            PtStatsIndexerHitRateDTO dto = new PtStatsIndexerHitRateDTO();
            dto.setIndexerId(indexer.getId());
            dto.setIndexerName(indexer.getName());
            long accepted = row == null ? 0 : asLong(row.get("accepted_count"));
            long rejected = row == null ? 0 : asLong(row.get("rejected_count"));
            dto.setAcceptedCount(accepted);
            dto.setRejectedCount(rejected);
            long denom = accepted + rejected;
            dto.setHasData(denom > 0);
            dto.setHitRate(denom > 0 ? Math.round(accepted * 10000.0 / denom) / 10000.0 : 0.0);
            result.add(dto);
        }
        return result;
    }

    /**
     * 失败原因分布：fail_reason 只由 DownloadTrackService.fail() 写入，固定两种文案，
     * 直接按原始字符串 GROUP BY 即可，不做归一化(设计文档2.1)。
     */
    public List<PtStatsFailReasonDTO> failReasons(int days) {
        java.time.LocalDate start = java.time.LocalDate.now().minusDays(days - 1L);
        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query()
                        .select("fail_reason as reason, count(*) as count")
                        .eq("state", STATE_FAILED)
                        .ge("pushed_time", start.atStartOfDay())
                        .groupBy("fail_reason")
                        .orderByDesc("count"));

        return rows.stream().map(row -> {
            PtStatsFailReasonDTO dto = new PtStatsFailReasonDTO();
            dto.setReason(String.valueOf(row.get("reason")));
            dto.setCount(asLong(row.get("count")));
            return dto;
        }).toList();
    }

    /**
     * 搜索淘汰原因分布：按 {@code pt_search_log.reason_code} 聚合被过滤规则淘汰的候选。
     * <p>
     * 与 {@link #failReasons} 对称但口径不同——那边是「推送之后下载失败」，这边是
     * 「候选在推送之前就被规则挡掉」。后者此前完全没有统计，而它恰恰是「订阅一直补不到货」
     * 最常见的原因：用户自己开的 freeOnly、分辨率白名单能把整批候选清空，而系统只会说
     * 「未找到可用资源」。
     * </p>
     * <p>
     * 按<b>码</b>聚合而不是按 reason 文案：文案里嵌着实际值，按文案 GROUP BY 只会得到
     * 一堆计数为 1 的碎片。与 {@link #indexerHitRate} 同理不做 days 筛选——
     * {@code pt_search_log} 本身按订阅保留 ≤200 条，再叠加时间筛选口径会不一致。
     * </p>
     */
    public List<PtStatsRejectReasonDTO> rejectReasons() {
        List<Map<String, Object>> rows = searchLogService.listMaps(
                Wrappers.<PtSearchLogPlus>query()
                        .select("reason_code as reason_code, count(*) as count")
                        .eq("accepted", "0")
                        .isNotNull("reason_code")
                        .groupBy("reason_code")
                        .orderByDesc("count"));

        return rows.stream().map(row -> {
            PtStatsRejectReasonDTO dto = new PtStatsRejectReasonDTO();
            String code = String.valueOf(row.get("reason_code"));
            dto.setCode(code);
            dto.setReason(RejectCode.labelOf(code));
            dto.setCount(asLong(row.get("count")));
            return dto;
        }).toList();
    }

    /**
     * Top 活跃订阅：按 sub_id 分组的下载次数排行，limit 走 QueryWrapper.last("LIMIT n")
     * (跟 SearchLogService 里清理旧日志用的同一种写法，n 是后端已校验过的白名单/上限值，无拼接风险)。
     * 订阅已被删除时(historical download record 还在但 pt_subscription 查不到)兜底展示，不抛 NPE。
     */
    public List<PtStatsActiveSubscriptionDTO> topSubscriptions(int days, int limit) {
        java.time.LocalDate start = java.time.LocalDate.now().minusDays(days - 1L);
        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query()
                        .select("sub_id as sub_id, count(*) as download_count, "
                                + "SUM(CASE WHEN state='" + STATE_COMPLETED + "' THEN 1 ELSE 0 END) as completed_count, "
                                + "SUM(CASE WHEN state='" + STATE_FAILED + "' THEN 1 ELSE 0 END) as failed_count")
                        .ge("pushed_time", start.atStartOfDay())
                        .groupBy("sub_id")
                        .orderByDesc("download_count")
                        .last("LIMIT " + limit));

        List<Integer> subIds = rows.stream().map(r -> ((Number) r.get("sub_id")).intValue()).toList();
        Map<Integer, PtSubscriptionPlus> subs = subIds.isEmpty() ? Map.of() : subscriptionService.listByIds(subIds)
                .stream().collect(java.util.stream.Collectors.toMap(PtSubscriptionPlus::getId, s -> s));

        return rows.stream().map(row -> {
            int subId = ((Number) row.get("sub_id")).intValue();
            PtSubscriptionPlus sub = subs.get(subId);
            PtStatsActiveSubscriptionDTO dto = new PtStatsActiveSubscriptionDTO();
            dto.setSubId(subId);
            dto.setTitle(sub == null ? "（订阅已删除）" : sub.getTitle());
            dto.setSeason(sub == null ? null : sub.getSeason());
            dto.setMediaType(sub == null ? null : sub.getMediaType());
            dto.setDownloadCount(asLong(row.get("download_count")));
            dto.setCompletedCount(asLong(row.get("completed_count")));
            dto.setFailedCount(asLong(row.get("failed_count")));
            dto.setLastMatchTime(sub == null ? null : sub.getLastMatchTime());
            return dto;
        }).toList();
    }

    private static long asLong(Object v) {
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    private static Double asDouble(Object v) {
        return v == null ? null : Double.parseDouble(v.toString());
    }
}
