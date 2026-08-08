package com.osr.openliststrm.orphan;

import java.util.List;

/**
 * 重命名一致性检查：扫描孤儿记录、清理确认后的孤儿、忽略误报。
 */
public interface IRenameOrphanScanService {

    /**
     * 全量扫描，两个方向都跑：
     * <ul>
     *   <li>正向（记录 → 文件）：遍历已重命名成功的记录，检测本地产物 / 网盘源是否仍然存在</li>
     *   <li>反向（文件 → 记录）：遍历目标库目录，找出无主媒体文件、只剩元数据的目录、空目录</li>
     * </ul>
     * 结果落库到 rename_orphan（新增 / 更新 / 自动移除已恢复正常的记录）。
     *
     * @return 本次扫描汇总
     */
    ScanSummary scan();

    /**
     * 批量确认清理。按 reason 分派：
     * <ul>
     *   <li>local_missing / source_missing：删本地产物 + 刮削文件 + 空目录 + rename_detail 记录</li>
     *   <li>local_extra：删该媒体文件 + 同名 NFO + 空目录</li>
     *   <li>metadata_only：删目录内的元数据文件并回收目录</li>
     *   <li>empty_dir：回收目录</li>
     * </ul>
     * 并把对应 rename_orphan 记录标记为已清理。
     */
    void clean(List<Integer> orphanIds);

    /**
     * 批量忽略：仅标记 rename_orphan 记录为已忽略，不做任何文件操作。
     */
    void ignore(List<Integer> orphanIds);

    /**
     * 一次扫描的汇总结果。
     *
     * @param localMissing  有记录但本地产物已丢失
     * @param sourceMissing 有记录、本地产物在，但网盘源已删
     * @param resolved      本轮确认已恢复正常、从待处理列表移除的
     * @param unparsable    STRM 内容解析不出网盘路径而跳过的
     * @param localExtra    目标库里无主的媒体文件
     * @param metadataOnly  只剩元数据的目录
     * @param emptyDir      空目录
     * @param truncated     因单轮上限被丢弃、未落库的反向发现数（不是 0 就说明这轮没扫全）
     */
    record ScanSummary(int localMissing, int sourceMissing, int resolved, int unparsable,
                       int localExtra, int metadataOnly, int emptyDir, int truncated) {
    }
}
