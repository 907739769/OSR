package com.osr.openliststrm.pt.downloader;

import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载器的连通状态，由 {@code DownloadTrackTask} 每轮拉种子列表时记录，供首页「待办提醒」读取。
 * <p>
 * <b>只放内存、不落库</b>：追踪任务每 30 秒一轮，重启后半分钟内就重新有数，
 * 为此给下载器表加列并每轮写库不划算（媒体服务器那边落库，是因为它要显示在配置页上、且对账周期长）。
 * 条目数等于下载器台数，不会无界增长；恢复时直接移除。
 *
 * @author Jack
 */
@Component
public class DownloaderHealthRegistry {

    /** 连续失败几轮才算「离线」：一次超时多半是抖动，报出来只会让人白跑一趟 */
    public static final int OFFLINE_THRESHOLD = 2;

    private final Map<Integer, Failure> failures = new ConcurrentHashMap<>();

    /**
     * 一台下载器当前的失败状态。
     *
     * @param consecutive 连续失败轮数
     * @param since       这一串失败从什么时候开始
     * @param lastError   最近一次失败原因
     */
    public record Failure(int consecutive, Date since, String lastError) {
    }

    public void recordSuccess(Integer downloaderId) {
        if (downloaderId != null) {
            failures.remove(downloaderId);
        }
    }

    public void recordFailure(Integer downloaderId, String error) {
        if (downloaderId == null) {
            return;
        }
        failures.merge(downloaderId, new Failure(1, new Date(), error),
                (old, now) -> new Failure(old.consecutive() + 1, old.since(), error));
    }

    /** 已连续失败到 {@link #OFFLINE_THRESHOLD} 的下载器，null 表示正常或还不够判为离线 */
    public Failure offline(Integer downloaderId) {
        Failure failure = failures.get(downloaderId);
        return failure != null && failure.consecutive() >= OFFLINE_THRESHOLD ? failure : null;
    }
}
