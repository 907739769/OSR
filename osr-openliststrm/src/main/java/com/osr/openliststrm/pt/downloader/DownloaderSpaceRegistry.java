package com.osr.openliststrm.pt.downloader;

import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 各下载器最近一次读到的剩余空间，以及「低于告警线」从什么时候开始。
 * <p>
 * <b>只放内存、不落库</b>，取向同 {@link DownloaderHealthRegistry}：每 15 分钟读一次，重启后一轮就重新有数；
 * 为一个随时在变的数字每轮写一次配置表不划算。条目数等于下载器台数，不会无界增长。
 * </p>
 */
@Component
public class DownloaderSpaceRegistry {

    /**
     * @param freeBytes 剩余空间（字节）
     * @param checkedAt 读到的时刻
     */
    public record Snapshot(long freeBytes, Date checkedAt) {
    }

    /**
     * 低于告警线这一段的状态。
     *
     * @param since        从什么时候开始低于告警线
     * @param lastNotified 上次发通知的时刻（开始那一次或之后的提醒）
     */
    public record LowState(Date since, Date lastNotified) {
    }

    private final Map<Integer, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<Integer, LowState> lows = new ConcurrentHashMap<>();

    public void record(Integer downloaderId, long freeBytes) {
        if (downloaderId != null) {
            snapshots.put(downloaderId, new Snapshot(freeBytes, new Date()));
        }
    }

    /** 最近一次读到的剩余空间；还没读到过（刚启动、或这台读不到）返回 null */
    public Snapshot snapshot(Integer downloaderId) {
        return downloaderId == null ? null : snapshots.get(downloaderId);
    }

    /** 当前低于告警线的状态；没有低于返回 null */
    public LowState low(Integer downloaderId) {
        return downloaderId == null ? null : lows.get(downloaderId);
    }

    public void markLow(Integer downloaderId, LowState state) {
        if (downloaderId != null) {
            lows.put(downloaderId, state);
        }
    }

    /** @return 之前确实处于低位（这次是恢复） */
    public boolean clearLow(Integer downloaderId) {
        return downloaderId != null && lows.remove(downloaderId) != null;
    }

    /** 登记表里出现过的全部下载器 id（读数或低位任一） */
    public Set<Integer> knownIds() {
        Set<Integer> ids = new HashSet<>(snapshots.keySet());
        ids.addAll(lows.keySet());
        return ids;
    }

    /** 下载器被停用 / 删除时一并忘掉，否则首页会一直挂着一台已经不存在的下载器 */
    public void forget(Integer downloaderId) {
        if (downloaderId != null) {
            snapshots.remove(downloaderId);
            lows.remove(downloaderId);
        }
    }
}
