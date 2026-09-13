package com.osr.openliststrm.pt.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 搜索补集的结果，返回给前端展示成功/无结果提示。
 * 手动选择模式时，candidates 字段包含所有候选种子供用户挑选。
 *
 * @author Jack
 */
@Data
@Builder
@AllArgsConstructor
public class SupplementResult {

    /** 是否成功找到并推送了一个种子（非手动模式时有效） */
    private boolean pushed;

    /** 本次搜索汇总到的候选种子总数（过滤前），供排查"搜到了但全被过滤掉"的情况 */
    private int candidateCount;

    /** 手动选择模式下的候选种子列表，非手动模式为 null 或空列表 */
    private List<SearchCandidateDTO> candidates;

    /**
     * 自动推送没推成时的原因，可直接展示给用户；推成了或手动选择模式下为 null。
     * <p>
     * 此前前端拿到 {@code pushed=false} 只能一律提示「未搜索到匹配资源」，而真实原因可能是
     * 50 个候选被过滤规则清光、下载器并发已满、该集刚被 RSS 占位——用户照着那句话去改关键词，
     * 方向完全错了。文案与落进匹配日志的摘要是同一份（见 {@code SearchLogService#latestSummarySince}）。
     * </p>
     */
    private String reason;

    /** 本次推送成功的资源个数。整季搜索会逐集推送，可能不止一个；单集/电影推成了恒为 1 */
    private int pushedCount;

    public SupplementResult(boolean pushed, int candidateCount) {
        this(pushed, candidateCount, null, null, pushed ? 1 : 0);
    }

    public SupplementResult(boolean pushed, int candidateCount, List<SearchCandidateDTO> candidates) {
        this(pushed, candidateCount, candidates, null, pushed ? 1 : 0);
    }

    public static SupplementResult miss(int candidateCount, String reason) {
        return new SupplementResult(false, candidateCount, null, reason, 0);
    }
}
