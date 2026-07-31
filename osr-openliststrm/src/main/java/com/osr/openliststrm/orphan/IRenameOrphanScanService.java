package com.osr.openliststrm.orphan;

import java.util.List;

/**
 * 重命名 STRM 一致性检查：扫描孤儿记录、清理确认后的孤儿、忽略误报。
 */
public interface IRenameOrphanScanService {

    /**
     * 全量扫描：遍历所有已重命名成功的 .strm 记录，检测本地文件/网盘源文件是否仍然存在，
     * 把检测结果落库到 rename_orphan（新增/更新/自动移除已恢复正常的记录）。
     *
     * @return 本次扫描汇总
     */
    ScanSummary scan();

    /**
     * 批量确认清理：删除残留的本地文件（仅 source_missing 需要）+ NFO/图片 + rename_detail 记录，
     * 并把对应 rename_orphan 记录标记为已清理。
     */
    void clean(List<Integer> orphanIds);

    /**
     * 批量忽略：仅标记 rename_orphan 记录为已忽略，不做任何文件操作。
     */
    void ignore(List<Integer> orphanIds);

    /**
     * 一次扫描的汇总结果。
     */
    record ScanSummary(int localMissing, int sourceMissing, int resolved, int unparsable) {
    }
}
