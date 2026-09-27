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
     * 下载器是否正在校验本地数据（含排队等待校验）。
     * <p>
     * 转移做种必须靠它区分「校验还没跑完」与「校验跑完了但数据对不上」：前者要继续等，
     * 后者说明目标下载器看到的路径下根本没有这份文件，必须把种子撤掉——放着不管的话，
     * 它会把整个种子当成新任务重新下载一遍。只看 {@link #progress} 分不出这两种情况，
     * 两者都表现为进度不到 1。
     * </p>
     * <p>
     * 自动删种的 {@code isBusy} 判定没有复用这个字段，是因为它拦的不止校验中一种状态
     * （还有移动中、错误态、文件丢失），语义比"是否在校验"宽。
     * </p>
     */
    private boolean checking;

    /**
     * 当前下载速度（字节/秒），下载记录页的实时速度用。
     * <p>
     * 瞬时值，<b>不落库</b>：它每秒都在变，写进下载记录只会产生一串没有意义的 UPDATE，
     * 而页面要的是「此刻多快」，由 {@code DownloadRecordControlService#live} 现问下载器即可。
     * </p>
     */
    private long downloadSpeed;

    /** 当前上传速度（字节/秒），瞬时值，不落库 */
    private long uploadSpeed;

    /**
     * 预计剩余秒数；下载器给不出（速度为 0、qB 的 8640000「无穷」、TR 的 -1/-2）时为 {@code null}。
     * <p>
     * 必须归一成 null 而不是透传：qB 用 8640000（100 天）表示算不出来，原样给前端会显示成
     * 「剩余 100 天」，看着像一个真实但离谱的估计，而实际意思是「不知道」。
     * </p>
     */
    private Long etaSeconds;

    /**
     * 种子在下载器里是否处于暂停（qB 4.x 的 paused*、5.x 的 stopped*，TR 的 status=0）。
     * <p>
     * 只用来<b>对账用户暂停</b>：用户在 OSR 里点了暂停、后来又直接在下载器里点了继续，
     * 下载记录上的暂停标记要跟着撤掉，否则僵尸超时会一直不计时。它不参与完成与失败判定。
     * </p>
     */
    private boolean paused;

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
