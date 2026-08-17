package com.osr.openliststrm.pt.downloader.model;

import lombok.Data;

/**
 * 种子内单个文件的信息，屏蔽各下载器的字段差异。
 *
 * @author Jack
 */
@Data
public class DownloaderTorrentFile {

    /** 文件在种子内的序号（下载器按此序号做文件选择） */
    private int index;

    /** 文件名/相对路径 */
    private String name;

    /** 文件大小（字节） */
    private long size;

    /**
     * 下载器是否要下载这个文件（qB 的 priority != 0 / TR 的 fileStats.wanted）。
     * <p>
     * 转移做种必须读它：导出的 .torrent 里<b>不含</b>文件优先级，源端「只下了其中几集」
     * 的种子原样加到目标端就是全选，校验后进度必然不到 100%，一次正常的转移会被判成
     * 「路径下没有这份数据」。目标端加种后要按源端的选择重新排除一遍未选中的文件。
     * </p>
     * <p>
     * 默认 true：下载器没给出这个字段时按「要下载」处理，最坏结果是多校验几个文件，
     * 反过来（默认 false）会把整个种子的文件全排除掉。
     * </p>
     */
    private boolean wanted = true;
}
