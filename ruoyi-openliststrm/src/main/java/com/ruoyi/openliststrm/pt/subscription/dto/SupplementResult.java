package com.ruoyi.openliststrm.pt.subscription.dto;

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

    public SupplementResult(boolean pushed, int candidateCount) {
        this.pushed = pushed;
        this.candidateCount = candidateCount;
        this.candidates = null;
    }
}
