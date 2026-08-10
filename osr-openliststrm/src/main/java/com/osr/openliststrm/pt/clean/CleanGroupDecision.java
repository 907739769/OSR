package com.osr.openliststrm.pt.clean;

import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import lombok.Getter;

import java.util.List;

/**
 * 对一个<b>辅种组</b>（共用同一份磁盘文件的一批种子）的清理判定结果。
 * <p>
 * 组是清理的最小单位而不是种子：删掉其中一个种子的文件，其余种子会立刻变成"文件丢失"，
 * 在各自站点上就是一次 H&R。因此整组同时达标才删，任一个不达标整组保留。
 * </p>
 *
 * @author Jack
 */
@Getter
public class CleanGroupDecision {

    /** 分组键，见 {@link DownloaderTorrent#contentKey()} */
    private final String contentKey;

    /** 组内全部种子（辅种时是同一份文件在多个站的多个种子） */
    private final List<DownloaderTorrent> torrents;

    /** 是否可以删除 */
    private final boolean deletable;

    /** 删除时是否连同文件一起删 */
    private final boolean deleteFiles;

    /** 不可删除时的原因；可删除时为 null */
    private final CleanSkipReason skipReason;

    /** 不可删除时"是哪个种子挡住的"，便于用户直接去下载器里看那一个 */
    private final String blockedBy;

    private CleanGroupDecision(String contentKey, List<DownloaderTorrent> torrents, boolean deletable,
                               boolean deleteFiles, CleanSkipReason skipReason, String blockedBy) {
        this.contentKey = contentKey;
        this.torrents = torrents;
        this.deletable = deletable;
        this.deleteFiles = deleteFiles;
        this.skipReason = skipReason;
        this.blockedBy = blockedBy;
    }

    public static CleanGroupDecision deletable(String contentKey, List<DownloaderTorrent> torrents,
                                               boolean deleteFiles) {
        return new CleanGroupDecision(contentKey, torrents, true, deleteFiles, null, null);
    }

    public static CleanGroupDecision skip(String contentKey, List<DownloaderTorrent> torrents,
                                          CleanSkipReason reason, String blockedBy) {
        return new CleanGroupDecision(contentKey, torrents, false, false, reason, blockedBy);
    }

    /**
     * 组内种子的体积。整组共用同一份文件，所以删掉整组释放的空间是<b>单个种子</b>的体积，
     * 不是各成员体积之和——把它们加起来会让"本轮释放了多少空间"虚报成辅种份数的倍数。
     */
    public long sizeBytes() {
        return torrents.stream().mapToLong(DownloaderTorrent::getSize).max().orElse(0L);
    }

    /** 组的展示名：取组内第一个种子的名字，辅种的名字通常一致 */
    public String displayName() {
        return torrents.isEmpty() ? contentKey : torrents.get(0).getName();
    }
}
