package com.osr.openliststrm.pt.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 批量拉黑下载记录（种子 GUID / 发布组维度）的执行结果。
 * <p>
 * 逐条复用单条拉黑，"已在黑名单中"与"解析不出发布组"都是预期内的结果而不是错误，
 * 分别计入 {@link #duplicateCount} 与 {@link #failedCount}，不因单条不满足条件让整批失败。
 * 批量拉黑发布组时同一发布组的多条记录只有第一条会真正落库，其余计入 duplicateCount。
 * </p>
 *
 * @author Jack
 */
@Data
@AllArgsConstructor
public class BatchBlacklistResult {

    /** 本次批量拉黑涉及的记录总数 */
    private int total;

    /** 新增进黑名单的条数 */
    private int addedCount;

    /** 命中幂等（拉黑目标已在黑名单中）的条数 */
    private int duplicateCount;

    /** 因记录不存在、标题解析不出发布组等原因未能拉黑的条数 */
    private int failedCount;
}
