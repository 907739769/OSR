package com.osr.openliststrm.pt.downloader.model;

import lombok.Data;

/**
 * 下载器中一个种子的状态快照，屏蔽各下载器的字段差异。
 *
 * @author Jack
 */
@Data
public class DownloaderTorrent {

    /** 种子 hash，统一为小写 */
    private String hash;

    /** 种子名称 */
    private String name;

    /** 下载进度，0.0 ~ 1.0 */
    private double progress;

    /** 下载器原始状态字符串，仅用于日志排查，不参与判定 */
    private String rawState;

    /** 保存路径 */
    private String savePath;

    /** 种子的标签，逗号分隔（qB 的 tags 字段原样保留），用于回映到下载记录 */
    private String tags;

    /**
     * 分享率（已上传 / 种子体积）。用于 H&R 保种考核。
     * <p>
     * 下载器未给出时为 0。Transmission 在无法计算时返回 -1，各客户端负责归一到 0——
     * 负数若原样透传，会让"分享率已达 hr_ratio"的比较在阈值为 0 时意外成立。
     * </p>
     */
    private double ratio;

    /**
     * 累计做种秒数。用于 H&R 保种考核（站点常见要求形如"做满 72 小时"）。
     * <p>
     * 注意这不是"下载完成到现在的墙上时间"：种子被暂停、下载器重启期间不计入，
     * 用下载器自己的口径才与站点的考核口径一致。
     * </p>
     */
    private long seedingSeconds;

    /** 累计上传字节数，仅供展示与排查，不参与达标判定 */
    private long uploaded;

    /**
     * 是否已下载完成。统一按进度判定，不依赖各下载器的状态枚举——
     * qBittorrent 的完成态有 uploading/stalledUP/pausedUP 等多种，
     * 且不同版本取值有差异，Transmission 的取值又完全不同。
     */
    public boolean isCompleted() {
        return progress >= 1.0;
    }
}
