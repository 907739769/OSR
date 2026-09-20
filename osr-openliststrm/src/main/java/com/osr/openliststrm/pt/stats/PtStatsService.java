package com.osr.openliststrm.pt.stats;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.osr.openliststrm.pt.task.FailReasonCode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PT 订阅统计仪表盘的聚合查询：全部用 QueryWrapper 原生 select/groupBy + IService.listMaps
 * 完成分组统计，不新建 XML Mapper，见设计文档 2.2 节。
 * <p>
 * 每个查询都带一个 {@link PtStatsScope}：统计面板与订阅列表共用同一条归属判据，
 * 非管理员只统计自己的订阅与无归属的公共订阅。
 * </p>
 *
 * @author Jack
 */
@Service
public class PtStatsService {

    private static final String STATE_COMPLETED = DownloadRecordState.COMPLETED.value();
    private static final String STATE_FAILED = DownloadRecordState.FAILED.value();
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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
    public PtStatsOverviewDTO overview(PtStatsScope scope) {
        PtStatsOverviewDTO dto = new PtStatsOverviewDTO();
        dto.setTotalSubscriptions(subscriptionService.count(scopedSubscriptions(scope)));
        dto.setActiveSubscriptions(subscriptionService.count(
                scopedSubscriptions(scope).eq("status", SubscriptionService.STATUS_ACTIVE)));

        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                scopedRecords(scope).select(
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
     * 下载量趋势：三条线各按<b>自己那件事发生的日期</b>分组，日期区间连续补齐。
     * <p>
     * <b>三条线不能共用 pushed_time 分组。</b>早先的实现把完成数、失败数一并挂在
     * 「推送日期」上，于是今天推送的种子今天多半还没下完，图上最后一天的完成数恒定趴在
     * 底部——看起来像"今天全挂了"，而那只是记账口径。同理，30 天前推送、昨天才失败的
     * 记录会记在 30 天前那一格，用户当天什么都看不到。现在：
     * </p>
     * <ul>
     *   <li>推送：{@code pushed_time}，专列</li>
     *   <li>完成：{@code completed_time}，专列，只在完成那一刻写一次；每日平均耗时随它一起算
     *       （耗时属于"这天完成的这批"，挂在推送日毫无意义）</li>
     *   <li>失败：{@code update_time} + {@code state='FAILED'}。失败没有专属时间列，而
     *       <b>处于 FAILED 状态的记录，它的 update_time 就是被判失败的那一刻</b>——H&R 采样
     *       只更新已完成的记录，进度回写只更新下载中的记录，重试会把状态改回 PUSHED
     *       而不再是 FAILED。这是个代理列，但在 FAILED 这个状态上它是准的。</li>
     * </ul>
     */
    public List<PtStatsTrendPointDTO> trend(int days, PtStatsScope scope) {
        LocalDate start = LocalDate.now().minusDays(days - 1L);

        Map<String, Map<String, Object>> pushed = byDay(downloadRecordService.listMaps(
                scopedRecords(scope)
                        .select("DATE_FORMAT(pushed_time,'%Y-%m-%d') as day, count(*) as cnt")
                        .ge("pushed_time", start.atStartOfDay())
                        .groupBy("DATE_FORMAT(pushed_time,'%Y-%m-%d')")));

        Map<String, Map<String, Object>> completed = byDay(downloadRecordService.listMaps(
                scopedRecords(scope)
                        .select("DATE_FORMAT(completed_time,'%Y-%m-%d') as day, count(*) as cnt, "
                                + "AVG(TIMESTAMPDIFF(MINUTE, pushed_time, completed_time)) as avg_duration_minutes")
                        .ge("completed_time", start.atStartOfDay())
                        .groupBy("DATE_FORMAT(completed_time,'%Y-%m-%d')")));

        Map<String, Map<String, Object>> failed = byDay(downloadRecordService.listMaps(
                scopedRecords(scope)
                        .select("DATE_FORMAT(update_time,'%Y-%m-%d') as day, count(*) as cnt")
                        .eq("state", STATE_FAILED)
                        .ge("update_time", start.atStartOfDay())
                        .groupBy("DATE_FORMAT(update_time,'%Y-%m-%d')")));

        List<PtStatsTrendPointDTO> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            String key = start.plusDays(i).format(DAY_FORMATTER);
            PtStatsTrendPointDTO point = new PtStatsTrendPointDTO();
            point.setDate(key);
            point.setPushedCount(count(pushed.get(key)));
            point.setCompletedCount(count(completed.get(key)));
            point.setFailedCount(count(failed.get(key)));
            Map<String, Object> completedRow = completed.get(key);
            point.setAvgDurationMinutes(completedRow == null ? null : asDouble(completedRow.get("avg_duration_minutes")));
            result.add(point);
        }
        return result;
    }

    /**
     * 索引器命中率：驱动集合是 pt_indexer 全量(indexerService.list())，不是只查有日志的索引器，
     * 新增索引器还没跑过时 hasData=false 也要出现在结果里(设计文档测试计划)。不做 days 筛选，
     * 见设计文档 2.4 节：pt_search_log 本身按订阅只保留最近若干条，再叠加时间筛选口径会不一致。
     */
    public List<PtStatsIndexerHitRateDTO> indexerHitRate(PtStatsScope scope) {
        List<Map<String, Object>> rows = searchLogService.listMaps(
                scopedLogs(scope)
                        .select("indexer_id as indexer_id, "
                                + "SUM(CASE WHEN accepted='1' THEN 1 ELSE 0 END) as accepted_count, "
                                + "SUM(CASE WHEN accepted='0' THEN 1 ELSE 0 END) as rejected_count")
                        .isNotNull("indexer_id")
                        .groupBy("indexer_id"));

        Map<Integer, Map<String, Object>> byIndexer = rows.stream()
                .collect(Collectors.toMap(r -> ((Number) r.get("indexer_id")).intValue(), Function.identity()));

        List<PtIndexerPlus> indexers = indexerService.list();
        List<PtStatsIndexerHitRateDTO> result = new ArrayList<>();
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
     * 失败原因分布：按 {@code fail_reason_code} 聚合，与 {@link #rejectReasons} 同一个姿势。
     * <p>
     * <b>不能按 {@code fail_reason} 文案聚合。</b>那一列里嵌着实际值——
     * {@code "种子内不含任何目标集（包内第 5,6 集，本次要补第 7 集）"}、
     * {@code "下载超过 24 小时仍未完成"}（小时数来自配置，用户一调就又裂出一类）——
     * 按文案 GROUP BY 得到的是一堆计数为 1 的碎片，饼图完全读不出"主要卡在哪"。
     * 这正是 {@link RejectCode} 当初存在的理由，失败侧的码（{@link FailReasonCode}）
     * 一直都在，只是统计这边没用上。
     * </p>
     * <p>
     * {@code fail_reason_code} 是 20260738 才加的列，更早的失败记录为 NULL，
     * 用 {@code COALESCE} 归到 {@code OTHER}，不让它在图上显示成一个叫 "null" 的扇形。
     * </p>
     */
    public List<PtStatsFailReasonDTO> failReasons(int days, PtStatsScope scope) {
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                scopedRecords(scope)
                        .select("COALESCE(fail_reason_code, 'OTHER') as code, count(*) as cnt")
                        .eq("state", STATE_FAILED)
                        // 与趋势图的失败线同口径：按"何时失败"而不是"何时推送"筛，
                        // 否则 30 天前推送、昨天才失败的记录在"近30天"里一条都看不到
                        .ge("update_time", start.atStartOfDay())
                        .groupBy("COALESCE(fail_reason_code, 'OTHER')")
                        .orderByDesc("cnt"));

        return rows.stream().map(row -> {
            PtStatsFailReasonDTO dto = new PtStatsFailReasonDTO();
            String code = row.get("code") == null ? FailReasonCode.OTHER.value() : String.valueOf(row.get("code"));
            dto.setCode(code);
            dto.setReason(FailReasonCode.labelOf(code));
            dto.setCount(asLong(row.get("cnt")));
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
     * {@code pt_search_log} 本身按订阅只保留最近若干条，再叠加时间筛选口径会不一致。
     * </p>
     */
    public List<PtStatsRejectReasonDTO> rejectReasons(PtStatsScope scope) {
        List<Map<String, Object>> rows = searchLogService.listMaps(
                scopedLogs(scope)
                        .select("reason_code as reason_code, count(*) as cnt")
                        .eq("accepted", "0")
                        .isNotNull("reason_code")
                        .groupBy("reason_code")
                        .orderByDesc("cnt"));

        return rows.stream().map(row -> {
            PtStatsRejectReasonDTO dto = new PtStatsRejectReasonDTO();
            String code = String.valueOf(row.get("reason_code"));
            dto.setCode(code);
            dto.setReason(RejectCode.labelOf(code));
            dto.setCount(asLong(row.get("cnt")));
            return dto;
        }).toList();
    }

    /**
     * Top 活跃订阅：按 sub_id 分组的下载次数排行，limit 走 QueryWrapper.last("LIMIT n")
     * (跟 SearchLogService 里清理旧日志用的同一种写法，n 是后端已校验过的白名单/上限值，无拼接风险)。
     * 订阅已被删除时(historical download record 还在但 pt_subscription 查不到)兜底展示，不抛 NPE。
     */
    public List<PtStatsActiveSubscriptionDTO> topSubscriptions(int days, int limit, PtStatsScope scope) {
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                scopedRecords(scope)
                        .select("sub_id as sub_id, count(*) as download_count, "
                                + "SUM(CASE WHEN state='" + STATE_COMPLETED + "' THEN 1 ELSE 0 END) as completed_count, "
                                + "SUM(CASE WHEN state='" + STATE_FAILED + "' THEN 1 ELSE 0 END) as failed_count")
                        .ge("pushed_time", start.atStartOfDay())
                        .groupBy("sub_id")
                        .orderByDesc("download_count")
                        .last("LIMIT " + limit));

        List<Integer> subIds = rows.stream().map(r -> ((Number) r.get("sub_id")).intValue()).toList();
        Map<Integer, PtSubscriptionPlus> subs = subIds.isEmpty() ? Map.of() : subscriptionService.listByIds(subIds)
                .stream().collect(Collectors.toMap(PtSubscriptionPlus::getId, Function.identity()));

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

    /** 订阅表上的归属条件 */
    private QueryWrapper<PtSubscriptionPlus> scopedSubscriptions(PtStatsScope scope) {
        QueryWrapper<PtSubscriptionPlus> wrapper = Wrappers.query();
        if (!scope.all()) {
            Long userId = scope.userId();
            if (userId == null) {
                // 取不到当前用户时只放行无归属的公共订阅。不能把 null 交给 eq()——
                // MyBatis-Plus 会生成 `owner_user_id = NULL`，在 SQL 里恒为 unknown
                wrapper.isNull("owner_user_id");
            } else {
                wrapper.and(w -> w.eq("owner_user_id", userId).or().isNull("owner_user_id"));
            }
        }
        return wrapper;
    }

    /** 下载记录表上的归属条件（经 sub_id 反查订阅） */
    private QueryWrapper<PtDownloadRecordPlus> scopedRecords(PtStatsScope scope) {
        QueryWrapper<PtDownloadRecordPlus> wrapper = Wrappers.query();
        String sql = scope.visibleSubIdSql();
        if (sql != null) {
            wrapper.inSql("sub_id", sql);
        }
        return wrapper;
    }

    /** 匹配日志表上的归属条件（经 sub_id 反查订阅） */
    private QueryWrapper<PtSearchLogPlus> scopedLogs(PtStatsScope scope) {
        QueryWrapper<PtSearchLogPlus> wrapper = Wrappers.query();
        String sql = scope.visibleSubIdSql();
        if (sql != null) {
            wrapper.inSql("sub_id", sql);
        }
        return wrapper;
    }

    private static Map<String, Map<String, Object>> byDay(List<Map<String, Object>> rows) {
        return rows.stream()
                .filter(r -> r.get("day") != null)
                .collect(Collectors.toMap(r -> String.valueOf(r.get("day")), Function.identity(), (a, b) -> a));
    }

    private static long count(Map<String, Object> row) {
        return row == null ? 0L : asLong(row.get("cnt"));
    }

    private static long asLong(Object v) {
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    private static Double asDouble(Object v) {
        return v == null ? null : Double.parseDouble(v.toString());
    }
}
