package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /** 每个订阅最多保留的日志条数，超出的旧记录清理掉，避免无限增长 */
    private static final int RETENTION_PER_SUBSCRIPTION = 200;

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
