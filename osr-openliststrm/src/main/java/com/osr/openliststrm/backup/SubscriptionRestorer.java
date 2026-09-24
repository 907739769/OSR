package com.osr.openliststrm.backup;

import com.osr.common.utils.Threads;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 在后台逐条重建备份里的订阅。
 * <p>
 * <b>走 {@link SubscriptionService#subscribe} 重建，而不是把订阅行原样插回去</b>：订阅的集行
 * （每集的状态、播出日期、TMDb 对齐）与「哪些集已经在媒体库里」都属于<b>这台机器</b>的事实，
 * 从旧机器搬过来只会是错的。subscribe 会重新拉 TMDb、按本机媒体库对账，建出来的进度才可信。
 * 代价是每条订阅要调几次 TMDb，一百条订阅得跑几分钟——所以放后台，页面轮询进度。
 * <p>
 * <b>不触发建订阅后的补搜</b>（网页端建订阅会触发）：一次恢复几十上百条订阅，全都立刻补搜
 * 会在同一时刻把所有索引器打一遍，撞上站点限流。交给正常的 RSS 轮询与定期补搜慢慢补。
 *
 * @author Jack
 */
@Slf4j
@Component
public class SubscriptionRestorer {

    /** 状态里最多保留几条失败明细 */
    private static final int MAX_ERRORS = 50;

    @Autowired
    private SubscriptionService subscriptionBiz;
    @Autowired
    private IPtSubscriptionPlusService subscriptionService;

    private final Object lock = new Object();
    private Progress progress;

    /**
     * 一条待重建的订阅，引用已换成本机的 id。
     *
     * @param status 备份时的状态，只有「已暂停」会被还原；「已完成」由本机媒体库对账重新判定
     */
    record Seed(String tmdbId, String mediaType, Integer season, String display, String status,
                String upgradeEnabled, String autoSearch, String healthIgnored,
                String filterOverride, String downloadOverride, Integer downloaderId, Long ownerUserId) {
    }

    public boolean isRunning() {
        synchronized (lock) {
            return progress != null && progress.finishTime == null;
        }
    }

    /** 当前（或最近一次）恢复的进度；从没跑过时返回 null */
    public RestoreStatus status() {
        synchronized (lock) {
            return progress == null ? null : progress.snapshot();
        }
    }

    /**
     * 开始后台重建。
     *
     * @throws IllegalStateException 上一轮还没跑完
     */
    void start(List<Seed> seeds) {
        synchronized (lock) {
            if (progress != null && progress.finishTime == null) {
                throw new IllegalStateException("上一次订阅恢复还在进行中，请等它完成后再试");
            }
            progress = new Progress(seeds.size());
        }
        log.info("开始在后台恢复 {} 条订阅", seeds.size());
        // Threads.wrap：让后台这一串日志与发起恢复的那次请求共用一个 traceId
        Thread.ofVirtual().name("backup-restore-subscriptions").start(Threads.wrap(() -> run(seeds)));
    }

    private void run(List<Seed> seeds) {
        for (Seed seed : seeds) {
            try {
                restoreOne(seed);
                record(true, null);
            } catch (IllegalArgumentException e) {
                // subscribe 的入参/TMDb 类错误，文案本身就是给用户看的
                record(false, seed.display() + "：" + e.getMessage());
            } catch (Exception e) {
                log.warn("恢复订阅 {} 失败：{}", seed.display(), e.getMessage(), e);
                record(false, seed.display() + "：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        RestoreStatus done;
        synchronized (lock) {
            progress.finishTime = new Date();
            done = progress.snapshot();
        }
        log.info("订阅恢复完成：共 {} 条，新建 {} 条，失败 {} 条", done.total(), done.created(), done.failed());
    }

    private void restoreOne(Seed seed) {
        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(seed.mediaType());
        SubscribeRequest request = new SubscribeRequest();
        request.setTmdbId(seed.tmdbId());
        request.setMediaType(seed.mediaType());
        request.setSeason(movie ? null : seed.season());
        request.setDownloaderId(seed.downloaderId());
        request.setFilterOverride(seed.filterOverride());
        request.setOwnerUserId(seed.ownerUserId());
        PtSubscriptionPlus sub = subscriptionBiz.subscribe(request);

        // subscribe 只认建订阅那几个入参，其余开关在这里补回去
        PtSubscriptionPlus patch = new PtSubscriptionPlus();
        patch.setId(sub.getId());
        boolean dirty = false;
        if (SubscriptionService.STATUS_PAUSED.equals(seed.status())
                && SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            patch.setStatus(SubscriptionService.STATUS_PAUSED);
            dirty = true;
        }
        dirty |= copyIfPresent(seed.upgradeEnabled(), patch::setUpgradeEnabled);
        dirty |= copyIfPresent(seed.autoSearch(), patch::setAutoSearch);
        dirty |= copyIfPresent(seed.healthIgnored(), patch::setHealthIgnored);
        dirty |= copyIfPresent(seed.downloadOverride(), patch::setDownloadOverride);
        if (dirty) {
            subscriptionService.updateById(patch);
        }
    }

    private static boolean copyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        setter.accept(value);
        return true;
    }

    private void record(boolean ok, String error) {
        synchronized (lock) {
            progress.processed++;
            if (ok) {
                progress.created++;
            } else {
                progress.failed++;
                if (progress.errors.size() < MAX_ERRORS) {
                    progress.errors.add(error);
                }
            }
        }
    }

    /**
     * 恢复进度的快照。
     *
     * @param running    是否还在跑
     * @param total      本轮要重建的条数
     * @param processed  已处理条数
     * @param created    新建成功条数
     * @param failed     失败条数
     * @param errors     失败明细（最多 {@value MAX_ERRORS} 条）
     * @param startTime  开始时间
     * @param finishTime 结束时间，未结束为 null
     */
    public record RestoreStatus(boolean running, int total, int processed, int created, int failed,
                                List<String> errors, Date startTime, Date finishTime) {
    }

    /** 可变的进度，只在 lock 内读写 */
    private static final class Progress {
        private final int total;
        private final Date startTime = new Date();
        private int processed;
        private int created;
        private int failed;
        private final List<String> errors = new ArrayList<>();
        private Date finishTime;

        Progress(int total) {
            this.total = total;
        }

        RestoreStatus snapshot() {
            return new RestoreStatus(finishTime == null, total, processed, created, failed,
                    List.copyOf(errors), startTime, finishTime);
        }
    }
}
