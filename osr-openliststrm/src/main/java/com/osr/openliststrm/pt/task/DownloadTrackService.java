package com.osr.openliststrm.pt.task;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.ws.PtStatusWebSocket;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.model.MediaInfo;
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
    private final IPtIndexerPlusService indexerService;

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
                                IPtIndexerPlusService indexerService,
                                @Value("${pt.download.max-consecutive-failures:3}") int maxConsecutiveFailures,
                                @Value("${pt.download.zombie-timeout-hours:24}") int zombieTimeoutHoursDefault) {
        this.recordService = recordService;
        this.episodeService = episodeService;
        this.completionSyncTrigger = completionSyncTrigger;
        this.subscriptionService = subscriptionService;
        this.downloaderClientFactory = downloaderClientFactory;
        this.indexerService = indexerService;
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
        if (!active.isEmpty()) {
            trackActive(downloader, torrents, active);
        }
        trackSeeding(downloader, torrents);
    }

    /** 在途记录（PUSHED/DOWNLOADING）的状态推进 */
    private void trackActive(PtDownloaderPlus downloader, List<DownloaderTorrent> torrents,
                             List<PtDownloadRecordPlus> active) {
        Map<Integer, PtSubscriptionPlus> subCache = loadSubscriptions(active);
        long now = System.currentTimeMillis();
        for (PtDownloadRecordPlus record : active) {
            DownloaderTorrent matched = findByTag(torrents, record.getTrackingTag());
            long age = record.getPushedTime() == null
                    ? Long.MAX_VALUE : now - record.getPushedTime().getTime();
            long zombieTimeoutMillis = resolveZombieTimeoutMillis(subCache.get(record.getSubId()));
            if (matched != null && matched.isCompleted()) {
                // 设限要赶在判完成之前：complete() 会把记录移出本查询的范围，
                // 而下载器的自动管理恰恰是在"下载完成"这一刻开始按限额清算的
                applyShareLimitsIfNeeded(downloader, record, matched);
                complete(record, downloader, matched);
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
                // H&R 限额越早下发越好：种子刚加进来时下载器就可能按全局限额安排它的命运
                applyShareLimitsIfNeeded(downloader, record, matched);
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
     * 保种中记录（COMPLETED 且 hr_state=PENDING）的 H&R 考核推进。
     * <p>
     * 这批记录不在 {@link #trackActive} 的查询范围内——它们已经是 COMPLETED 终态了。
     * 原先的实现到此为止就不再看这个种子，于是做种时长和分享率无人过问：用户既不知道
     * 哪些种子还不能删，也不知道自己什么时候已经踩了雷。
     * </p>
     */
    private void trackSeeding(PtDownloaderPlus downloader, List<DownloaderTorrent> torrents) {
        List<PtDownloadRecordPlus> seeding = recordService.listSeedingPending(downloader.getId());
        if (seeding == null || seeding.isEmpty()) {
            return;
        }
        Map<Integer, PtIndexerPlus> indexerCache = loadIndexers(seeding);
        for (PtDownloadRecordPlus record : seeding) {
            PtIndexerPlus indexer = indexerCache.get(record.getIndexerId());
            DownloaderTorrent matched = findByTag(torrents, record.getTrackingTag());
            if (matched == null) {
                // OSR 自己从不删种，走到这里说明是用户手动删了、或下载器的自动管理把它清掉了。
                // 无法补救，只能如实告知，让用户去站点申诉或重新做种。
                violate(record);
                continue;
            }
            if (indexer == null) {
                // 索引器被删了，考核标准无从谈起。保持 PENDING 只会每轮空转，
                // 直接收尾并说明原因，比让用户对着一个永远不动的"保种中"发懵好
                log.warn("下载记录[{}] 的来源索引器已不存在，H&R 考核无从判定，按已达标收尾", record.getId());
                satisfy(record, matched, "来源索引器已删除，无法继续考核");
                continue;
            }
            if (isHitAndRunSatisfied(indexer, matched)) {
                satisfy(record, matched, describeRequirement(indexer));
            } else {
                sampleSeedingProgress(record, matched);
            }
        }
    }

    /**
     * 是否已满足站点的 H&R 要求。
     * <p>
     * 时长与分享率是<b>或</b>的关系——PT 站的通行表述是"做满 N 小时<b>或</b>分享率达到 R"，
     * 任一条满足即解除考核。站点只配了其中一项时，另一项的阈值为 0，
     * {@link PtIndexerPlus#hitAndRunEnabled()} 已经保证至少有一项是有效阈值。
     * </p>
     */
    private boolean isHitAndRunSatisfied(PtIndexerPlus indexer, DownloaderTorrent torrent) {
        Integer seedHours = indexer.getHrSeedHours();
        if (seedHours != null && seedHours > 0 && torrent.getSeedingSeconds() >= seedHours * 3600L) {
            return true;
        }
        Double ratio = indexer.getHrRatio();
        return ratio != null && ratio > 0 && torrent.getRatio() >= ratio;
    }

    private String describeRequirement(PtIndexerPlus indexer) {
        StringBuilder sb = new StringBuilder();
        if (indexer.getHrSeedHours() != null && indexer.getHrSeedHours() > 0) {
            sb.append("做满 ").append(indexer.getHrSeedHours()).append(" 小时");
        }
        if (indexer.getHrRatio() != null && indexer.getHrRatio() > 0) {
            if (sb.length() > 0) {
                sb.append(" 或 ");
            }
            sb.append("分享率 ").append(indexer.getHrRatio());
        }
        return sb.toString();
    }

    /** 尚未达标：只把采样值落库供前端展示"还差多少"，不改状态、不发通知 */
    private void sampleSeedingProgress(PtDownloadRecordPlus record, DownloaderTorrent torrent) {
        PtDownloadRecordPlus set = new PtDownloadRecordPlus();
        set.setHrSeedSeconds(torrent.getSeedingSeconds());
        set.setHrRatio(torrent.getRatio());
        recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .eq("hr_state", HitAndRunState.PENDING.value()));
    }

    /** 达标：条件更新门控通知，避免重叠轮询重复发 */
    private void satisfy(PtDownloadRecordPlus record, DownloaderTorrent torrent, String requirement) {
        PtDownloadRecordPlus set = new PtDownloadRecordPlus();
        set.setHrState(HitAndRunState.SATISFIED.value());
        set.setHrSeedSeconds(torrent.getSeedingSeconds());
        set.setHrRatio(torrent.getRatio());
        set.setHrSatisfiedTime(new Date());
        boolean changed = recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .eq("hr_state", HitAndRunState.PENDING.value()));
        if (!changed) {
            return;
        }
        log.info("下载记录[{}] H&R 已达标（{}）：{}", record.getId(), requirement, record.getTitle());
        notifySafely(NotificationType.DOWNLOAD_COMPLETE, "🌱 H&R 已达标，可安全删除："
                + StringUtils.escapeHtml(record.getTitle())
                + "\n满足条件：" + StringUtils.escapeHtml(requirement)
                + "\n当前做种 " + formatHours(torrent.getSeedingSeconds())
                + "，分享率 " + String.format("%.2f", torrent.getRatio()));
    }

    /** 达标前种子就消失了：H&R 已经产生，只能如实告知 */
    private void violate(PtDownloadRecordPlus record) {
        PtDownloadRecordPlus set = new PtDownloadRecordPlus();
        set.setHrState(HitAndRunState.VIOLATED.value());
        boolean changed = recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .eq("hr_state", HitAndRunState.PENDING.value()));
        if (!changed) {
            return;
        }
        long seeded = record.getHrSeedSeconds() == null ? 0L : record.getHrSeedSeconds();
        log.warn("下载记录[{}] 在 H&R 达标前从下载器消失，可能已产生 H&R：{}", record.getId(), record.getTitle());
        notifySafely(NotificationType.DOWNLOAD_FAILED, "⚠️ 可能已产生 H&R："
                + StringUtils.escapeHtml(record.getTitle())
                + "\n该种子在满足站点保种要求前就从下载器中消失了（最后一次采样：做种 "
                + formatHours(seeded) + "）。OSR 不会删除种子，请检查是否被手动删除或被下载器的做种限额清理，"
                + "并尽快到站点确认是否需要补种或申诉");
    }

    /** 把秒数说成人能读的小时，通知里用 */
    private String formatHours(long seconds) {
        if (seconds < 3600) {
            return seconds / 60 + " 分钟";
        }
        return String.format("%.1f 小时", seconds / 3600.0);
    }

    /**
     * 按来源站点的 H&R 规则给下载器里的这个种子下发分享限额，幂等。
     * <p>
     * 只在站点确实开了 H&R 考核时下发——没有考核的站点不该被 OSR 擅自改动种子设置。
     * 任何异常只记 warn、不置标记，留给下一轮 30 秒轮询重试，与 {@link #trySelectFiles} 同样的容错取向。
     * </p>
     */
    private void applyShareLimitsIfNeeded(PtDownloaderPlus downloader, PtDownloadRecordPlus record,
                                          DownloaderTorrent matched) {
        if (Boolean.TRUE.equals(record.getHrLimitsApplied()) || StringUtils.isBlank(matched.getHash())) {
            return;
        }
        PtIndexerPlus indexer = indexerService.getById(record.getIndexerId());
        if (indexer == null || !indexer.hitAndRunEnabled()) {
            return;
        }
        try {
            long seedingMinutes = indexer.getHrSeedHours() == null ? 0L : indexer.getHrSeedHours() * 60L;
            double ratio = indexer.getHrRatio() == null ? 0.0 : indexer.getHrRatio();
            downloaderClientFactory.get(downloader)
                    .setShareLimits(downloader, matched.getHash(), ratio, seedingMinutes);
            PtDownloadRecordPlus set = new PtDownloadRecordPlus();
            set.setHrLimitsApplied(true);
            recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>().eq("id", record.getId()));
            record.setHrLimitsApplied(true);
        } catch (Exception e) {
            log.warn("下载记录[{}] 下发 H&R 分享限额失败，下一轮重试：{}", record.getId(), e.getMessage());
        }
    }

    /** 批量加载本次要处理的记录涉及的索引器，避免循环内逐条查询 */
    private Map<Integer, PtIndexerPlus> loadIndexers(List<PtDownloadRecordPlus> records) {
        List<Integer> ids = records.stream()
                .map(PtDownloadRecordPlus::getIndexerId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return indexerService.listByIds(ids).stream()
                .collect(Collectors.toMap(PtIndexerPlus::getId, i -> i));
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

    private void notifySafely(NotificationType type, String msg) {
        try {
            TgHelper.sendMsg(type, msg);
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }

    private void complete(PtDownloadRecordPlus record, PtDownloaderPlus downloader, DownloaderTorrent matched) {
        // 来源站点开了 H&R 考核就进入保种追踪，否则 hr_state 保持 null（不适用）。
        // 完成这一刻是唯一能决定它的时机：之后记录就不再经过 trackActive 了。
        PtIndexerPlus indexer = indexerService.getById(record.getIndexerId());
        boolean hitAndRun = indexer != null && indexer.hitAndRunEnabled();

        PtDownloadRecordPlus set = new PtDownloadRecordPlus();
        set.setState(STATE_COMPLETED);
        set.setProgress(1.0);
        set.setCompletedTime(new Date());
        if (hitAndRun) {
            set.setHrState(HitAndRunState.PENDING.value());
            set.setHrSeedSeconds(matched.getSeedingSeconds());
            set.setHrRatio(matched.getRatio());
        }
        boolean changed = recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .in("state", STATE_PUSHED, STATE_DOWNLOADING));
        if (!changed) {
            return; // 并发/重叠轮询已处理过，避免重复通知
        }
        PtStatusWebSocket.pushDownloadEvent(record, STATE_COMPLETED, 1.0, null);
        notifySafely(NotificationType.DOWNLOAD_COMPLETE, "✅ 下载完成：" + StringUtils.escapeHtml(record.getTitle())
                + (hitAndRun ? "\n🌱 该站点有 H&R 考核，需保种至「" + StringUtils.escapeHtml(describeRequirement(indexer))
                        + "」，达标前请勿删除" : ""));
        log.info("下载记录[{}] 已完成：{}{}", record.getId(), record.getTitle(),
                hitAndRun ? "（进入 H&R 保种考核）" : "");
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
        notifySafely(NotificationType.DOWNLOAD_FAILED, "❌ 下载失败：" + StringUtils.escapeHtml(record.getTitle()) + "，已释放待下轮重新匹配");
        log.warn("下载记录[{}] 失败，{} 个集回退缺失：{}", record.getId(), episodes.size(), record.getTitle());
        if (blockedCount > 0) {
            notifySafely("🚫 " + StringUtils.escapeHtml(record.getTitle()) + " 连续失败达 " + maxConsecutiveFailures
                    + " 次，已停止自动重试，需到下载记录管理页人工重试");
        }
    }
}
