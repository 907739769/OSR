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
     * 种子体积（字节），自动删种的体积区间判定用。
     * <p>
     * 取的是「实际会落到盘上的体积」而不是种子声明的总体积——OSR 会给多集包排除非目标集文件
     * （见 {@code IDownloaderClient#excludeFiles}），两者可以差出一个数量级，
     * 而清理关心的是"删掉它能腾出多少空间"。
     * </p>
     */
    private long size;

    /**
     * 内容路径：种子在盘上的落地位置（单文件种子是文件本身，多文件种子是根目录）。
     * <p>
     * 这是判定「辅种」的键：IYUU 把同一份文件在多个站的种子都加到下载器里时，
     * 它们的 hash 各不相同，但内容路径完全一致。删掉其中一个的文件会让其余的立刻变成
     * 错误状态并在各自站点记 H&R，因此清理必须以内容路径为单位整组处理。
     * </p>
     * <p>
     * 下载器没有给出时为 null，此时调用方退化用 {@code savePath + name} 作为分组键。
     * </p>
     */
    private String contentPath;

    /**
     * 辅种分组键：优先用下载器给的 {@link #contentPath}，缺失时退化为「保存路径 + 种子名」。
     * <p>
     * 两者都拿不到时返回 hash，等于"这个种子自成一组"——宁可把一组拆散成多组
     * （最坏结果是文件删不掉、空间没腾出来），也不能把不相干的种子并成一组后连坐删除。
     * </p>
     */
    public String contentKey() {
        if (contentPath != null && !contentPath.isBlank()) {
            return contentPath;
        }
        if (savePath != null && !savePath.isBlank() && name != null && !name.isBlank()) {
            return savePath + "/" + name;
        }
        return hash;
    }

    /**
     * 是否已下载完成。统一按进度判定，不依赖各下载器的状态枚举——
     * qBittorrent 的完成态有 uploading/stalledUP/pausedUP 等多种，
     * 且不同版本取值有差异，Transmission 的取值又完全不同。
     */
    public boolean isCompleted() {
        return progress >= 1.0;
    }
}
