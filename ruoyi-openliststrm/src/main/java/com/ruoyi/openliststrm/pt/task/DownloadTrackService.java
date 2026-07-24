package com.ruoyi.openliststrm.pt.task;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.helper.TgHelper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionEpisodeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 下载追踪的编排逻辑：把下载器里的种子回映到下载记录并推进状态。
 * 抽成独立 Service 是为了脱离定时器单测。
 *
 * @author Jack
 */
@Slf4j
@Service
public class DownloadTrackService {

    private static final String STATE_PUSHED = DownloadRecordState.PUSHED.value();
    private static final String STATE_DOWNLOADING = DownloadRecordState.DOWNLOADING.value();
    private static final String STATE_COMPLETED = DownloadRecordState.COMPLETED.value();
    private static final String STATE_FAILED = DownloadRecordState.FAILED.value();
    private static final String EP_MISSING = SubscriptionEpisodeState.MISSING.value();
    private static final String EP_IN_FLIGHT = SubscriptionEpisodeState.IN_FLIGHT.value();
    private static final String EP_BLOCKED = SubscriptionEpisodeState.BLOCKED.value();

    /** 推送后找不到对应种子的宽限期：超过它才判失败（qB 解析磁力元数据需要时间） */
    private static final long GRACE_MILLIS = 10 * 60 * 1000L;

    /** 同一集连续失败达到该次数后不再回退 MISSING，转 BLOCKED 停止自动重试，避免已下架/失效资源被无限次静默重试 */
    private final int maxConsecutiveFailures;

    /** 附录C 绝对时长兜底的全局默认值：推送超过该时长仍未完成的记录一律判失败并回退集，
     *  覆盖「种子还在下载器但 0 做种卡死」这类 grace 分支照不到的僵尸种子。
     *  订阅可通过 pt_subscription.download_override 里的 zombieTimeoutHours 键覆盖此默认值。
     *  代价：真实的超长慢速下载超过该时长也会被释放（其 guid 按附录B 拉黑，该集靠别的种子恢复）。 */
    private final long zombieTimeoutMillisDefault;

    private final IPtDownloadRecordPlusService recordService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final DownloadCompletionSyncTrigger completionSyncTrigger;
    private final IPtSubscriptionPlusService subscriptionService;

    public DownloadTrackService(IPtDownloadRecordPlusService recordService,
                                IPtSubscriptionEpisodePlusService episodeService,
                                DownloadCompletionSyncTrigger completionSyncTrigger,
                                IPtSubscriptionPlusService subscriptionService,
                                @Value("${pt.download.max-consecutive-failures:3}") int maxConsecutiveFailures,
                                @Value("${pt.download.zombie-timeout-hours:24}") int zombieTimeoutHoursDefault) {
        this.recordService = recordService;
        this.episodeService = episodeService;
        this.completionSyncTrigger = completionSyncTrigger;
        this.subscriptionService = subscriptionService;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.zombieTimeoutMillisDefault = zombieTimeoutHoursDefault * 3600_000L;
    }

    /**
     * 追踪一个下载器：拉回来的种子已按公共标签过滤过，这里只做状态推进。
     */
    public void track(PtDownloaderPlus downloader, List<DownloaderTorrent> torrents) {
        List<PtDownloadRecordPlus> active = recordService.list(
                new QueryWrapper<PtDownloadRecordPlus>()
                        .eq("downloader_id", downloader.getId())
                        .in("state", STATE_PUSHED, STATE_DOWNLOADING));
        if (active.isEmpty()) {
            return;
        }
        Map<Integer, PtSubscriptionPlus> subCache = loadSubscriptions(active);
        long now = System.currentTimeMillis();
        for (PtDownloadRecordPlus record : active) {
            DownloaderTorrent matched = findByTag(torrents, record.getTrackingTag());
            long age = record.getPushedTime() == null
                    ? Long.MAX_VALUE : now - record.getPushedTime().getTime();
            long zombieTimeoutMillis = resolveZombieTimeoutMillis(subCache.get(record.getSubId()));
            if (matched != null && matched.isCompleted()) {
                complete(record, downloader);
            } else if (matched == null) {
                if (age >= GRACE_MILLIS) {
                    fail(record, "下载器中已找不到该种子（可能被删除或元数据解析失败）");
                }
                // 未超宽限期：qB 可能还在解析元数据，本轮跳过
            } else {
                // 种子还在下载器但未完成
                if (age >= zombieTimeoutMillis) {
                    fail(record, "下载超过 " + (zombieTimeoutMillis / 3600000) + " 小时仍未完成，判定为僵尸种子");
                } else {
                    markDownloading(record, matched.getProgress());
                }
            }
        }
    }

    /**
     * 批量加载本次要处理的记录涉及的全部订阅，循环内按 subId 取用，避免逐条查询（批内缓存，
     * 与问题 1 的 {@code SubscriptionEngine} 批内缓存原则一致）。
     */
    private Map<Integer, PtSubscriptionPlus> loadSubscriptions(List<PtDownloadRecordPlus> records) {
        List<Integer> subIds = records.stream().map(PtDownloadRecordPlus::getSubId).distinct().toList();
        if (subIds.isEmpty()) {
            return Map.of();
        }
        return subscriptionService.listByIds(subIds).stream()
                .collect(Collectors.toMap(PtSubscriptionPlus::getId, s -> s));
    }

    /**
     * 解析订阅级僵尸超时覆盖：只有 downloadOverride JSON 里出现 zombieTimeoutHours 键才覆盖，
     * 格式损坏、值非法（&lt;=0）或订阅为 null（已被删除）时一律回退全局默认值，
     * 绝不让一条脏配置炸掉整轮轮询。写法与 {@code FilterCriteriaFactory} 同源。
     */
    private long resolveZombieTimeoutMillis(PtSubscriptionPlus sub) {
        if (sub == null || StringUtils.isBlank(sub.getDownloadOverride())) {
            return zombieTimeoutMillisDefault;
        }
        try {
            JSONObject patch = JSONObject.parseObject(sub.getDownloadOverride());
            if (patch != null && patch.containsKey("zombieTimeoutHours")) {
                Integer hours = patch.getInteger("zombieTimeoutHours");
                if (hours != null && hours > 0) {
                    return hours * 3600_000L;
                }
            }
        } catch (Exception e) {
            log.warn("订阅[{}] 下载追踪覆盖不是合法 JSON，已回退全局默认值：{}", sub.getId(), e.getMessage());
        }
        return zombieTimeoutMillisDefault;
    }

    /**
     * 置为下载中并同步进度。进度每轮都可能变化，即使状态已是 DOWNLOADING 也要持久化，
     * 否则前端下载记录页看到的进度永远停在第一次写入时的值。
     */
    private void markDownloading(PtDownloadRecordPlus record, double progress) {
        record.setState(STATE_DOWNLOADING);
        record.setProgress(progress);
        recordService.updateById(record);
    }

    private DownloaderTorrent findByTag(List<DownloaderTorrent> torrents, String trackingTag) {
        if (StringUtils.isBlank(trackingTag)) {
            return null;
        }
        for (DownloaderTorrent torrent : torrents) {
            if (StringUtils.isBlank(torrent.getTags())) {
                continue;
            }
            for (String tag : torrent.getTags().split(",")) {
                if (trackingTag.equals(tag.trim())) {
                    return torrent;
                }
            }
        }
        return null;
    }

    /**
     * 发通知但绝不让通知失败影响主流程。TgHelper 未配置时本就静默返回；
     * 单测环境下 SpringUtils.getBean 会抛异常，这里一并兜住。
     */
    private void notifySafely(String msg) {
        try {
            TgHelper.sendMsg(msg);
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }

    private void complete(PtDownloadRecordPlus record, PtDownloaderPlus downloader) {
        PtDownloadRecordPlus set = new PtDownloadRecordPlus();
        set.setState(STATE_COMPLETED);
        set.setProgress(1.0);
        set.setCompletedTime(new Date());
        boolean changed = recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .in("state", STATE_PUSHED, STATE_DOWNLOADING));
        if (!changed) {
            return; // 并发/重叠轮询已处理过，避免重复通知
        }
        notifySafely("✅ 下载完成：" + record.getTitle());
        log.info("下载记录[{}] 已完成：{}", record.getId(), record.getTitle());
        // 集状态不动，仍是 IN_FLIGHT；下载器关联了 STRM 任务时异步触发一次增量生成+提前对账，
        // 没关联时纯靠 LibrarySyncTask 下一轮批量对账兜底
        completionSyncTrigger.triggerAsync(record, downloader);
    }

    /**
     * 判记录失败并回退其关联集。反转写序（先集、后记录）保证崩溃安全：
     * 无论崩在哪一步，记录仍处于 PUSHED/DOWNLOADING，会被下一轮重新处理，
     * 不会产生「记录已 FAILED 但集仍 IN_FLIGHT」的永久孤儿。
     * <p>
     * 每个关联集各自累加连续失败次数：达到阈值前回退 MISSING（RSS/补搜会重新捡回），
     * 达到阈值后转 BLOCKED 停止自动重试，避免已下架/失效的资源被无限次静默重试。
     * </p>
     */
    private void fail(PtDownloadRecordPlus record, String reason) {
        // 1) 先回退关联集（幂等：只动 IN_FLIGHT 的；普通集1条、季包多条统一处理）
        List<PtSubscriptionEpisodePlus> episodes = episodeService.list(
                new QueryWrapper<PtSubscriptionEpisodePlus>()
                        .eq("download_id", record.getId())
                        .eq("state", EP_IN_FLIGHT));
        int blockedCount = 0;
        for (PtSubscriptionEpisodePlus episode : episodes) {
            int fails = (episode.getFailCount() == null ? 0 : episode.getFailCount()) + 1;
            boolean blocked = fails >= maxConsecutiveFailures;
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(blocked ? EP_BLOCKED : EP_MISSING);
            set.setDownloadId(null);
            set.setFailCount(fails);
            episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", episode.getId())
                    .eq("state", EP_IN_FLIGHT));
            if (blocked) {
                blockedCount++;
            }
        }
        // 2) 再置记录 FAILED（条件更新门控通知，避免重叠轮询重复发）
        PtDownloadRecordPlus set = new PtDownloadRecordPlus();
        set.setState(STATE_FAILED);
        set.setFailReason(reason);
        boolean changed = recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .in("state", STATE_PUSHED, STATE_DOWNLOADING));
        if (!changed) {
            return; // 已被并发轮次置为终态，避免重复通知
        }
        notifySafely("❌ 下载失败：" + record.getTitle() + "，已释放待下轮重新匹配");
        log.warn("下载记录[{}] 失败，{} 个集回退缺失：{}", record.getId(), episodes.size(), record.getTitle());
        if (blockedCount > 0) {
            notifySafely("🚫 " + record.getTitle() + " 连续失败达 " + maxConsecutiveFailures
                    + " 次，已停止自动重试，需到下载记录管理页人工重试");
        }
    }
}
