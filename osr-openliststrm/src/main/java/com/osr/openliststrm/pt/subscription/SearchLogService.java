package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.pt.filter.RejectCode;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 匹配/过滤日志的落库与保留策略。只在候选已匹配到某个订阅时才记录——
 * RSS 全量拉取里跟任何订阅都不沾边的种子没有订阅上下文可归属，仍然只走 debug 日志。
 *
 * @author Jack
 */
@Slf4j
@Service
public class SearchLogService {

    public static final String SOURCE_RSS = "RSS";
    public static final String SOURCE_SUPPLEMENT = "SUPPLEMENT";
    /**
     * 用户在手动搜索列表里点选某个候选推送。
     * <p>
     * 与 SUPPLEMENT 分开不只是为了日志好看：{@code SubscriptionEngine} 用它判断这次推送是不是
     * 人工决定，从而放行「有不可重试失败记录」的候选（见 {@code SubscriptionEngine#excludeAlreadyRecorded}）。
     * 判据只此一处、不另设并行的布尔参数，免得来源与语义各说各话。
     * </p>
     */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 每个订阅最多保留的日志条数，超出的旧记录清理掉，避免无限增长 */
    private static final int RETENTION_PER_SUBSCRIPTION = 200;

    /** 淘汰原因摘要里最多列举几类，够用户判断方向即可，完整明细逐条躺在表里 */
    private static final int SUMMARY_TOP_N = 3;

    private final IPtSearchLogPlusService logService;

    public SearchLogService(IPtSearchLogPlusService logService) {
        this.logService = logService;
    }

    /**
     * 记录一批候选种子的过滤裁决（通过/淘汰+原因）。
     * 日志写入失败不该影响主流程（推送/匹配），因此这里兜底吞异常，只记 warn。
     */
    public void recordVerdicts(Integer subId, int episode, String source, List<TorrentFilterEngine.Verdict> verdicts) {
        if (subId == null || verdicts.isEmpty()) {
            return;
        }
        try {
            List<PtSearchLogPlus> rows = verdicts.stream().map(v -> {
                PtSearchLogPlus row = new PtSearchLogPlus();
                row.setSubId(subId);
                row.setEpisode(episode);
                row.setSource(source);
                row.setTorrentTitle(v.torrent().getTitle());
                row.setIndexerId(v.torrent().getIndexerId());
                row.setAccepted(v.accepted() ? "1" : "0");
                row.setReasonCode(v.rejectCode() == null ? null : v.rejectCode().value());
                row.setReason(v.rejectReason());
                return row;
            }).toList();
            logService.saveBatch(rows);
            prune(subId);
        } catch (Exception e) {
            log.warn("写匹配日志失败（不影响主流程），订阅[{}]：{}", subId, e.getMessage());
        }
    }

    /**
     * 记录一条没有候选明细的摘要日志（如"无可用下载器""无可占位缺集"）。
     */
    public void recordSummary(Integer subId, int episode, String source, String reason) {
        if (subId == null) {
            return;
        }
        try {
            PtSearchLogPlus row = new PtSearchLogPlus();
            row.setSubId(subId);
            row.setEpisode(episode);
            row.setSource(source);
            row.setAccepted("0");
            row.setReason(reason);
            logService.save(row);
            prune(subId);
        } catch (Exception e) {
            log.warn("写匹配日志失败（不影响主流程），订阅[{}]：{}", subId, e.getMessage());
        }
    }

    /**
     * 取该订阅当前日志的最大 id，作为一次搜索的<b>水位线</b>：搜索结束后用
     * {@link #summarizeRejectionsSince} 只聚合水位线之后新写入的行，从而精确对应「这一次搜索」。
     * <p>
     * 用自增 id 而不是 {@code create_time}：id 单调且由数据库生成，不依赖时间字段被正确填充，
     * 也不受同一秒内多次写入、时钟回拨影响。订阅还没有任何日志时返回 0。
     * </p>
     */
    public long watermark(Integer subId) {
        if (subId == null) {
            return 0L;
        }
        try {
            PtSearchLogPlus latest = logService.getOne(new LambdaQueryWrapper<PtSearchLogPlus>()
                    .eq(PtSearchLogPlus::getSubId, subId)
                    .orderByDesc(PtSearchLogPlus::getId)
                    .last("limit 1"), false);
            return latest == null || latest.getId() == null ? 0L : latest.getId();
        } catch (Exception e) {
            log.warn("读取匹配日志水位线失败（不影响主流程），订阅[{}]：{}", subId, e.getMessage());
            return 0L;
        }
    }

    /**
     * 把水位线之后写入的<b>淘汰行</b>按 {@code reason_code} 聚合成一句人话，供通知说明
     * 「为什么这次什么都没推成」。
     * <p>
     * 这是 {@code reason_code} 列存在的主要理由之一：按 {@code reason} 文本聚合会因为文案里
     * 嵌着实际值（做种数、体积、标签名）而碎成一堆计数为 1 的片段，看不出主要卡在哪条规则上。
     * </p>
     * <p>
     * 只取前 {@link #SUMMARY_TOP_N} 类，够用户判断方向即可；完整明细本来就逐条躺在
     * {@code pt_search_log} 里。没有任何淘汰行（例如压根没搜到候选）时返回 {@code null}，
     * 调用方据此退回原本的泛化文案——那种情况确实与过滤规则无关。
     * </p>
     */
    public String summarizeRejectionsSince(Integer subId, long watermark) {
        if (subId == null) {
            return null;
        }
        try {
            List<PtSearchLogPlus> rejected = logService.list(new LambdaQueryWrapper<PtSearchLogPlus>()
                    .eq(PtSearchLogPlus::getSubId, subId)
                    .gt(PtSearchLogPlus::getId, watermark)
                    .eq(PtSearchLogPlus::getAccepted, "0")
                    .isNotNull(PtSearchLogPlus::getReasonCode));
            if (rejected.isEmpty()) {
                return null;
            }
            Map<String, Long> byCode = rejected.stream().collect(Collectors.groupingBy(
                    PtSearchLogPlus::getReasonCode, LinkedHashMap::new, Collectors.counting()));
            String detail = byCode.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(SUMMARY_TOP_N)
                    .map(e -> e.getValue() + " 个「" + RejectCode.labelOf(e.getKey()) + "」")
                    .collect(Collectors.joining("、"));
            return rejected.size() + " 个候选被过滤规则淘汰：" + detail;
        } catch (Exception e) {
            log.warn("聚合淘汰原因失败（不影响主流程），订阅[{}]：{}", subId, e.getMessage());
            return null;
        }
    }

    /** 保留策略：每个订阅只留最近 {@link #RETENTION_PER_SUBSCRIPTION} 条，超出的按 id 从旧到新删 */
    private void prune(Integer subId) {
        long count = logService.count(new LambdaQueryWrapper<PtSearchLogPlus>().eq(PtSearchLogPlus::getSubId, subId));
        if (count <= RETENTION_PER_SUBSCRIPTION) {
            return;
        }
        List<PtSearchLogPlus> stale = logService.list(new LambdaQueryWrapper<PtSearchLogPlus>()
                .eq(PtSearchLogPlus::getSubId, subId)
                .orderByAsc(PtSearchLogPlus::getId)
                .last("limit " + (count - RETENTION_PER_SUBSCRIPTION)));
        if (!stale.isEmpty()) {
            logService.removeByIds(stale.stream().map(PtSearchLogPlus::getId).toList());
        }
    }
}
