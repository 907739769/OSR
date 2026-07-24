package com.ruoyi.openliststrm.pt.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 批量暂停/恢复订阅的执行结果。
 * <p>
 * 逐条复用单条 pause/resume，一条订阅已被并发删除等原因导致失败时不影响同批次其余条目，
 * 失败的 id 收进 {@link #failedIds} 供前端提示"M 项已跳过（可能已被删除）"。
 * </p>
 *
 * @author Jack
 */
@Data
@AllArgsConstructor
public class BatchOperationResult {

    /** 成功处理的条数 */
    private int successCount;

    /** 因订阅不存在等原因被跳过的 id 列表 */
    private List<Integer> failedIds;
}
