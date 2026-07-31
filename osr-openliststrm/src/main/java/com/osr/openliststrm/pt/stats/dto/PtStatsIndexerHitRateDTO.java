package com.osr.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * 索引器命中率：驱动集合是 pt_indexer 全量（不是只有产生过日志的），
 * 从未在 pt_search_log 里出现过的索引器 hasData=false，不参与图表比例计算。
 *
 * @author Jack
 */
@Data
public class PtStatsIndexerHitRateDTO {

    private Integer indexerId;

    private String indexerName;

    /** 通过过滤的候选数 */
    private long acceptedCount;

    /** 被淘汰的候选数 */
    private long rejectedCount;

    /** 命中率，0~1；分母(accepted+rejected)为0时记0 */
    private double hitRate;

    /** 该索引器是否在 pt_search_log 中出现过匹配记录 */
    private boolean hasData;
}
