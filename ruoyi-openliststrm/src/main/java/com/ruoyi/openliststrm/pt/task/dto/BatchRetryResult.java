package com.ruoyi.openliststrm.pt.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 批量重试失败下载记录的执行结果。
 * <p>
 * 逐条复用单条 retry，记录已被并发处理成非 FAILED、关联订阅已暂停等情况计入 {@link #skippedCount}，
 * 不因单条不满足条件让整批失败。
 * </p>
 *
 * @author Jack
 */
@Data
@AllArgsConstructor
public class BatchRetryResult {

    /** 本次批量重试涉及的记录总数 */
    private int total;

    /** 重新找到候选并成功推送下载的条数 */
    private int pushedCount;

    /** 未搜到候选、或因状态不满足重试条件而被跳过的条数 */
    private int skippedCount;
}
