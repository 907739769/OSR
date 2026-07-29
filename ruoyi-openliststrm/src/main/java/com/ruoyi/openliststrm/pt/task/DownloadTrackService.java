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
import com.ruoyi.openliststrm.pt.downloader.DownloaderClientFactory;
import com.ruoyi.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.ruoyi.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.ruoyi.openliststrm.pt.ws.PtStatusWebSocket;
import com.ruoyi.openliststrm.rename.MediaParser;
import com.ruoyi.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final DownloaderClientFactory downloaderClientFactory;

    /**
     * MediaParser 不是 Spring bean（一直靠 new 管理，见 SubscriptionEngine 同名字段注释），
     * 若走构造器注入会导致本类装配时找不到对应 bean 而启动失败。这里只做本地正则解析，
     * 传 null 客户端与 SubscriptionEngine.fillParsed 用法一致，不查 TMDb、不调 AI。
     */
    private final MediaParser mediaParser = new MediaParser(null, null);

    public DownloadTrackService(IPtDownloadRecordPlusService recordService,
                                IPtSubscriptionEpisodePlusService episodeService,
                                DownloadCompletionSyncTrigger completionSyncTrigger,
                                IPtSubscriptionPlusService subscriptionService,
                                DownloaderClientFactory downloaderClientFactory,
                                @Value("${pt.download.max-consecutive-failures:3}") int maxConsecutiveFailures,
                                @Value("${pt.download.zombie-timeout-hours:24}") int zombieTimeoutHoursDefault) {
        this.recordService = recordService;
        this.episodeService = episodeService;
        this.completionSyncTrigger = completionSyncTrigger;
        this.subscriptionService = subscriptionService;
        this.downloaderClientFactory = downloaderClientFactory;
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
                    fail(record, FailReasonCode.TORRENT_NOT_FOUND, "下载器中已找不到该种子（可能被删除或元数据解析失败）");
                }
                // 未超宽限期：qB 可能还在解析元数据，本轮跳过
            } else {
                // 种子还在下载器但未完成：先尝试按目标集数过滤文件（幂等，仅第一次成功后才标记跳过）
                if (!Boolean.TRUE.equals(record.getFilesSelected())) {
                    trySelectFiles(downloader, record, matched);
                }
                if (age >= zombieTimeoutMillis) {
                    fail(record, FailReasonCode.ZOMBIE_TIMEOUT,
                            "下载超过 " + (zombieTimeoutMillis / 3600000) + " 小时仍未完成，判定为僵尸种子");
                } else {
                    markDownloading(record, matched.getProgress());
                }
            }
        }
    }

    /**
     * 按下载记录关联的目标集号，过滤种子内非目标集数的文件（排除下载）。
     * <p>
     * 目标集号来自 {@code pt_subscription_episode.download_id}——这张表是"缺集的唯一真相来源"，
     * 推送种子时占位的那批集已经写好了这个关联，这里直接复用，不必再猜"这条种子该覆盖哪几集"。
     * 单集下载天然只有 1 个目标集号，跑一遍本逻辑也无害（该保留的文件不会被排除）。
     * </p>
     * <p>
     * 任何异常都只记 warn、不标记 {@code filesSelected}，留给下一轮 30 秒轮询重试；
     * 种子刚加入下载器、元数据还没解析完成时 {@code listFiles} 会返回空列表，同样等下一轮重试。
     * </p>
     */
    private void trySelectFiles(PtDownloaderPlus downloader, PtDownloadRecordPlus record, DownloaderTorrent matched) {
        try {
            List<PtSubscriptionEpisodePlus> targets = episodeService.list(
                    new QueryWrapper<PtSubscriptionEpisodePlus>().eq("download_id", record.getId()));
            if (targets.isEmpty()) {
                // 理论上不该发生：claim() 推送种子时必然写好 download_id 关联。保守起见不标记
                // filesSelected，留给下一轮重试，避免异常数据下误吞掉本该处理的场景。
                return;
            }
            Set<Integer> targetEpisodes = targets.stream()
                    .map(PtSubscriptionEpisodePlus::getEpisode)
                    .collect(Collectors.toSet());

            List<DownloaderTorrentFile> files = downloaderClientFactory.get(downloader)
                    .listFiles(downloader, matched.getHash());
            if (files.isEmpty()) {
                // 元数据尚未解析完成，本轮跳过，不标记 selected
                return;
            }

            Set<Integer> excludeIndexes = new HashSet<>();
            for (DownloaderTorrentFile file : files) {
                MediaInfo info = mediaParser.parseLocal(file.getName());
                Integer episode = toInt(info.getEpisode());
                // 解析不出集号的文件（NFO、字幕、样片等）默认保留，不做排除
                if (episode != null && !targetEpisodes.contains(episode)) {
                    excludeIndexes.add(file.getIndex());
                }
            }
            if (!excludeIndexes.isEmpty()) {
                downloaderClientFactory.get(downloader).excludeFiles(downloader, matched.getHash(), excludeIndexes);
            }
            markFilesSelected(record);
        } catch (Exception e) {
            log.warn("下载记录[{}] 按目标集数过滤文件失败，下一轮重试：{}", record.getId(), e.getMessage());
        }
    }

    private void markFilesSelected(PtDownloadRecordPlus record) {
        record.setFilesSelected(true);
        recordService.updateById(record);
    }

    private Integer toInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
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
        PtStatusWebSocket.pushDownloadEvent(record, STATE_DOWNLOADING, progress, null);
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
        PtStatusWebSocket.pushDownloadEvent(record, STATE_COMPLETED, 1.0, null);
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
    private void fail(PtDownloadRecordPlus record, FailReasonCode code, String reason) {
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
        set.setFailReasonCode(code.value());
        boolean changed = recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .in("state", STATE_PUSHED, STATE_DOWNLOADING));
        if (!changed) {
            return; // 已被并发轮次置为终态，避免重复通知
        }
        PtStatusWebSocket.pushDownloadEvent(record, STATE_FAILED, null, reason);
        notifySafely("❌ 下载失败：" + record.getTitle() + "，已释放待下轮重新匹配");
        log.warn("下载记录[{}] 失败，{} 个集回退缺失：{}", record.getId(), episodes.size(), record.getTitle());
        if (blockedCount > 0) {
            notifySafely("🚫 " + record.getTitle() + " 连续失败达 " + maxConsecutiveFailures
                    + " 次，已停止自动重试，需到下载记录管理页人工重试");
        }
    }
}
