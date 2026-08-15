package com.osr.openliststrm.pt.transfer;

import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import lombok.Getter;

/**
 * 一个种子在本轮的转移判定结果。
 * <p>
 * 预览接口与真正的执行走<b>同一份</b>判定产物：分叉一次就会出现"预览里说会搬的、
 * 实际没搬"，那比不给预览更糟。
 * </p>
 *
 * @author Jack
 */
@Getter
public class TransferCandidate {

    private final DownloaderTorrent torrent;

    private final boolean transferable;

    private final TransferSkipReason skipReason;

    /**
     * 目标下载器视角下的保存路径（已应用路径映射）。
     * <p>
     * 即便判定为不可转移也尽量填上：路径映射配错是本功能最常见的故障，而预览页面上
     * 「源路径 → 目标路径」这一对值是用户唯一能一眼看出配错了的地方。
     * </p>
     */
    private final String targetSavePath;

    private TransferCandidate(DownloaderTorrent torrent, boolean transferable,
                              TransferSkipReason skipReason, String targetSavePath) {
        this.torrent = torrent;
        this.transferable = transferable;
        this.skipReason = skipReason;
        this.targetSavePath = targetSavePath;
    }

    public static TransferCandidate transferable(DownloaderTorrent torrent, String targetSavePath) {
        return new TransferCandidate(torrent, true, null, targetSavePath);
    }

    public static TransferCandidate skip(DownloaderTorrent torrent, TransferSkipReason reason,
                                         String targetSavePath) {
        return new TransferCandidate(torrent, false, reason, targetSavePath);
    }

    public String displayName() {
        return torrent.getName();
    }

    public long sizeBytes() {
        return torrent.getSize();
    }
}
