package com.osr.openliststrm.pt.task.dto;

import lombok.Data;

/**
 * 一条在途下载记录此刻在下载器里的实时状态，下载记录页每几秒拉一次。
 * <p>
 * 全是瞬时值，不落库：速度每秒都在变，写进下载记录只会产生一串没有意义的 UPDATE。
 * </p>
 *
 * @author Jack
 */
@Data
public class DownloadLiveView {

    private Integer id;

    /**
     * 这一轮在下载器里找到了没有。{@code false} 有两种可能：下载器连不上，或种子还在解析元数据 /
     * 已被删掉——前端两种都只是不显示速度，状态的变化交给下载追踪去判，这里不下结论
     */
    private boolean found;

    /** 下载速度（字节/秒） */
    private long downloadSpeed;

    /** 上传速度（字节/秒） */
    private long uploadSpeed;

    /** 预计剩余秒数，下载器算不出来时为 null */
    private Long etaSeconds;

    /** 下载器报告的实时进度 0~1，比落库的（30 秒一次）新 */
    private double progress;

    /** 种子在下载器里是否处于暂停 */
    private boolean paused;
}
