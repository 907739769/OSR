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
}
