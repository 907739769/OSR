package com.osr.openliststrm.pt.task;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyTarget;
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
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.upgrade.QualityProfile;
import com.osr.openliststrm.pt.upgrade.UpgradeState;
import com.osr.openliststrm.pt.ws.PtStatusWebSocket;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
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
    private static final String EP_IN_LIBRARY = SubscriptionEpisodeState.IN_LIBRARY.value();
    private static final String EP_UPGRADING = SubscriptionEpisodeState.UPGRADING.value();
    private static final String EP_BLOCKED = SubscriptionEpisodeState.BLOCKED.value();

    /** pt_subscription_episode.file_confirmed 的「已确认」取值 */
    static final String FILE_CONFIRMED = "1";

    /** 媒体类型：电影。判定只看 media_type，口径与 {@link SubscriptionService} 同源，不用 season==0（特别篇也是第0季） */
    private static final String TYPE_MOVIE = SubscriptionService.TYPE_MOVIE;

    /** 推送后找不到对应种子的宽限期：超过它才判失败（qB 解析磁力元数据需要时间） */
    private static final long GRACE_MILLIS = 10 * 60 * 1000L;

    /**
     * 种子在下载器里、但迟迟解析不出文件列表的容忍上限。
     * <p>
     * 这是<b>暂停加种</b>的必要兜底：多集包以暂停态推送，只有拿到文件列表选完目标集才会被启动。
     * 种子损坏、磁力无人做种、OSR 恰好在启动前重启……任何一种都会让它永远停在暂停态。
     * 不在这里收尾的话，它会占着下载器的并发名额直到僵尸超时（默认 24 小时），
     * 而僵尸超时判的是"下载不动"，跟"元数据都没拿到"根本是两回事。
     * </p>
     * <p>
     * 30 分钟是刻意给足的：.torrent 的文件列表本该秒级可得，磁力慢些但十几分钟也够了；
     * 判早了会误杀正在解析的种子，判晚了只是让一个本就废掉的任务多占半小时名额。
     * </p>
     * <p>
     * 判定还<b>必须叠加"进度为 0"</b>（见调用处）：文件选不出来的原因也可能是下载器 API
     * 临时故障，而种子本身下得好好的——只按时间判会把它一并删掉。
     * </p>
     */
    private static final long METADATA_TIMEOUT_MILLIS = 30 * 60 * 1000L;

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
                // 对账也要赶在判完成之前，理由同上：记录转 COMPLETED 后就再不经过 trackActive，
                // 这是最后一次能读到文件列表并给集打确认标记的机会
                confirmEpisodesIfNeeded(downloader, record, matched, subCache.get(record.getSubId()));
                complete(record, downloader, matched);
            } else if (matched == null) {
                if (age >= GRACE_MILLIS) {
                    fail(record, FailReasonCode.TORRENT_NOT_FOUND, "下载器中已找不到该种子（可能被删除或元数据解析失败）");
                }
                // 未超宽限期：qB 可能还在解析元数据，本轮跳过
            } else {
                // 种子还在下载器但未完成：先尝试按目标集数过滤文件（幂等，仅第一次成功后才标记跳过）
                if (!Boolean.TRUE.equals(record.getFilesSelected())
                        && trySelectFiles(downloader, record, matched, subCache.get(record.getSubId()))) {
                    // 包里没有任何目标集，记录已判失败、占位集已回退。绝不能再往下走：
                    // markDownloading 是无条件 updateById，会把刚置 FAILED 的记录复活成 DOWNLOADING
                    continue;
                }
                // H&R 限额越早下发越好：种子刚加进来时下载器就可能按全局限额安排它的命运
                applyShareLimitsIfNeeded(downloader, record, matched);
                if (!Boolean.TRUE.equals(record.getFilesSelected()) && matched.getProgress() <= 0) {
                    // 文件没选好 + 一个字节都没下 = 它还停在起跑线上：要么是暂停态的多集包在等
                    // 文件选择，要么是元数据根本没解析出来。标成 DOWNLOADING 会在前端显示成
                    // "下载中 0%"，保持 PUSHED（"已推送，未开始"）才是它真实的样子。
                    //
                    // progress > 0 的种子绝不能走这条路：文件选择失败的原因也可能是下载器 API
                    // 临时故障（listFiles 抛异常），而种子本身在正常下载。那种情况下按超时中止
                    // 会连带把一个下得好好的种子删掉，比不做这个兜底糟得多
                    if (age >= METADATA_TIMEOUT_MILLIS) {
                        abortMetadataTimeout(downloader, record, matched);
                    }
                    continue;
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
                + "，分享率 " + String.format("%.2f", torrent.getRatio()), ownerOf(record));
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
                + "并尽快到站点确认是否需要补种或申诉", ownerOf(record));
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
     *
     * @return 是否已<b>中止</b>这条记录（包内一个目标集都没有）。返回 true 时记录已是 FAILED 终态，
     *         调用方必须立刻跳过它——后续的 markDownloading 是无条件 updateById，会把它复活
     */
    private boolean trySelectFiles(PtDownloaderPlus downloader, PtDownloadRecordPlus record, DownloaderTorrent matched,
                                   PtSubscriptionPlus sub) {
        try {
            List<PtSubscriptionEpisodePlus> targets = episodeService.list(
                    new QueryWrapper<PtSubscriptionEpisodePlus>().eq("download_id", record.getId()));
            if (targets.isEmpty()) {
                // 理论上不该发生：claim() 推送种子时必然写好 download_id 关联。保守起见不标记
                // filesSelected，留给下一轮重试，避免异常数据下误吞掉本该处理的场景。
                return false;
            }
            Set<Integer> targetEpisodes = targets.stream()
                    .map(PtSubscriptionEpisodePlus::getEpisode)
                    .collect(Collectors.toSet());

            List<DownloaderTorrentFile> files = downloaderClientFactory.get(downloader)
                    .listFiles(downloader, matched.getHash());
            if (files.isEmpty()) {
                // 元数据尚未解析完成，本轮跳过，不标记 selected
                return false;
            }

            Set<Integer> excludeIndexes = new HashSet<>();
            Set<Integer> actualEpisodes = new HashSet<>();
            for (DownloaderTorrentFile file : files) {
                MediaInfo info = mediaParser.parseLocal(file.getName());
                Integer episode = toInt(info.getEpisode());
                if (episode == null) {
                    // 解析不出集号的文件（NFO、字幕、样片等）默认保留，不做排除
                    continue;
                }
                actualEpisodes.add(episode);
                if (!targetEpisodes.contains(episode)) {
                    excludeIndexes.add(file.getIndex());
                }
            }
            // 包内一个目标集都没有：绝不能把这批排除指令发下去。那会让下载器拿到一个所有
            // 视频文件都 prio=0 的任务，0 字节永远挂在那里占着并发名额，直到僵尸超时（默认
            // 24 小时）才收尾；qB 某些版本还会把"全部文件不下载"直接判成 completed，
            // 记录转 COMPLETED 后连僵尸兜底都够不着，只能等 12 小时后的卡死在途集清扫。
            // 这一步的判据（下载器给出的真实文件列表）已经足够精确，直接中止最干净。
            if (isNoTargetEpisode(sub, targetEpisodes, actualEpisodes)) {
                abortNoTargetEpisode(downloader, record, sub, matched, targetEpisodes, actualEpisodes);
                return true;
            }
            if (!excludeIndexes.isEmpty()) {
                downloaderClientFactory.get(downloader).excludeFiles(downloader, matched.getHash(), excludeIndexes);
            }
            // 文件选完才启动。多集包是以暂停态推送的（见 SubscriptionEngine#shouldPauseOnAdd），
            // 不启动它就永远不会开始下载；对没有暂停加入的种子（单集、磁力、电影）这是无害的空操作，
            // 因此不必记录"当初是不是暂停加进来的"。
            // 必须赶在 markFilesSelected 之前：这一步失败要留给下一轮重试，而一旦标了
            // filesSelected 就再也不会进到本方法，暂停的种子将永远没人启动
            downloaderClientFactory.get(downloader).resumeTorrent(downloader, matched.getHash());
            reconcileClaims(record, sub, targets, actualEpisodes);
            markFilesSelected(record);
        } catch (Exception e) {
            log.warn("下载记录[{}] 按目标集数过滤文件失败，下一轮重试：{}", record.getId(), e.getMessage());
        }
        return false;
    }

    /**
     * 种子里能解析出集号的文件，与本记录要补的集<b>一个都对不上</b>吗？
     * <p>
     * 三条保守约束与 {@link #reconcileClaims} 同源，宁可漏判也不能错判——错判会把一个
     * 正在正常下载的种子当场中止掉：
     * <ul>
     *   <li>{@code actualEpisodes} 为空时返回 false：那说明文件名一个集号都没解析出来
     *       （整季打包成单文件、命名奇特），不是"包里没有目标集"的证据。此时原有逻辑
     *       同样一个文件都不会排除，任务照常下载。</li>
     *   <li>电影订阅返回 false：电影集号是哨兵 0，文件名里随便一个数字都可能被解析成集号，
     *       比对必然假阳性，会把刚推送的电影整个中止掉。</li>
     *   <li>订阅为 null（已被删除）返回 false：没有判据就不动手，交给僵尸超时兜底。</li>
     * </ul>
     * </p>
     */
    private boolean isNoTargetEpisode(PtSubscriptionPlus sub, Set<Integer> targetEpisodes,
                                      Set<Integer> actualEpisodes) {
        if (sub == null || TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()) || actualEpisodes.isEmpty()) {
            return false;
        }
        return Collections.disjoint(targetEpisodes, actualEpisodes);
    }

    /**
     * 中止一条"包里没有任何目标集"的下载：判记录失败并回退占位集，让这些集重新参与搜索。
     * <p>
     * <b>不累加 {@code fail_count}</b>：与 {@link #reconcileClaims} 同一取向——这不是
     * "这一集补不到货"，而是季包的覆盖范围估错了。累加会让几次季包误判把一个明明能补到的集
     * 熔断成 BLOCKED，用户还得去下载记录页手动解封。
     * </p>
     * <p>
     * 通知里必须把"包内实际有哪几集 / 本次要补哪几集"说清楚：这是用户唯一能看懂
     * 「为什么这个种子刚推就没了」的地方，否则看起来就像系统随机丢任务。
     * 顺带提醒下载器里的空任务需要手动清——OSR 从不删种。
     * </p>
     */
    private void abortNoTargetEpisode(PtDownloaderPlus downloader, PtDownloadRecordPlus record,
                                      PtSubscriptionPlus sub, DownloaderTorrent matched,
                                      Set<Integer> targetEpisodes, Set<Integer> actualEpisodes) {
        String actual = joinEpisodes(actualEpisodes);
        String target = joinEpisodes(targetEpisodes);
        log.info("下载记录[{}] 包内实际含第 {} 集，与本次目标第 {} 集无交集，已中止：{}",
                record.getId(), actual, target, record.getTitle());
        doFail(record, FailReasonCode.NO_TARGET_EPISODE,
                "种子内不含任何目标集（包内第 " + actual + " 集，本次要补第 " + target + " 集）",
                false,
                "📦 种子内不含任何目标集：《" + StringUtils.escapeHtml(sub.getTitle()) + "》\n"
                        + StringUtils.escapeHtml(record.getTitle())
                        + "\n包内实际是第 " + actual + " 集，本次的目标是第 " + target
                        + " 集，已中止下载，相关集将重新参与后续匹配");
        // 多集包是暂停态推送的，走到这里它一个字节都没下过，删掉不留痕
        removeUselessTorrent(downloader, record, matched);
    }

    /**
     * 元数据迟迟解析不出来：判失败并把种子从下载器移除。
     * <p>
     * 见 {@link #METADATA_TIMEOUT_MILLIS} 的注释——这是暂停加种绕不开的兜底。
     * 回退占位集时<b>累加</b> {@code fail_count}（与普通下载失败同一取向）：
     * 解析不出文件列表的种子基本就是废种，同一集反复撞上它该被熔断，
     * 这一点与"包选错了"的 {@link #abortNoTargetEpisode} 不同。
     * </p>
     */
    private void abortMetadataTimeout(PtDownloaderPlus downloader, PtDownloadRecordPlus record,
                                      DownloaderTorrent matched) {
        log.warn("下载记录[{}] 超过 {} 分钟仍未解析出文件列表，已中止：{}",
                record.getId(), METADATA_TIMEOUT_MILLIS / 60000, record.getTitle());
        fail(record, FailReasonCode.METADATA_TIMEOUT,
                "超过 " + (METADATA_TIMEOUT_MILLIS / 60000) + " 分钟仍未解析出种子文件列表（种子损坏或无人做种）");
        removeUselessTorrent(downloader, record, matched);
    }

    /**
     * 把一个「什么有用数据都没下到」的种子从下载器移除，连同已下载的碎片。
     * <p>
     * 这是「OSR 从不删种」<b>唯一</b>的例外，因此边界必须是可证明的：<b>只删从未下载完成、
     * 也从未做种的种子</b>。站点的 H&R 考核从下载完成才开始计，这样的种子根本不在考核范围内，
     * 删它不可能记过。两个条件任一不成立就宁可留着让用户自己处置——留一个垃圾任务的代价，
     * 远小于误删一个正在保种的种子。
     * </p>
     * <p>
     * 删除失败只记 warn：记录已判失败、占位集已回退，这一步纯粹是打扫现场，
     * 不该反过来影响已经落定的主流程。
     * </p>
     */
    private void removeUselessTorrent(PtDownloaderPlus downloader, PtDownloadRecordPlus record,
                                      DownloaderTorrent matched) {
        if (matched == null || StringUtils.isBlank(matched.getHash())) {
            return;
        }
        if (matched.isCompleted() || matched.getSeedingSeconds() > 0) {
            log.info("下载记录[{}] 的种子已完成或已在做种，保留在下载器里由用户处置：{}",
                    record.getId(), record.getTitle());
            return;
        }
        try {
            downloaderClientFactory.get(downloader).deleteTorrent(downloader, matched.getHash(), true);
            log.info("下载记录[{}] 的无用种子已从下载器移除：{}", record.getId(), record.getTitle());
        } catch (Exception e) {
            log.warn("下载记录[{}] 从下载器移除种子失败（不影响已判失败的记录）：{}", record.getId(), e.getMessage());
        }
    }

    /** 集号排序后拼成人能读的列表，通知与失败原因共用 */
    private String joinEpisodes(Set<Integer> episodes) {
        return episodes.stream().sorted().map(String::valueOf).collect(Collectors.joining("、"));
    }

    /**
     * 认领对账：把这条记录占位的集与种子里<b>实际存在</b>的集比对，多占的退回缺失。
     * <p>
     * 这是修正季包过度占位的<b>精确判据</b>。{@code SubscriptionEngine} 给整季包占位的是订阅
     * 当时全部缺失集，而种子里究竟有哪些集，在推送那一刻是不知道的——番剧分成上/中/下、
     * 跨年续播时，一个"S01 季包"很可能只含 50 集里的前 26 集。多占的那些集下载完也不会入库，
     * Emby 对账只升不降（见 {@code SubscriptionService#refresh}）不会退它们，而补搜与 RSS
     * 只认 MISSING，于是它们永久停在在途：既下不到，也不再被搜索，还看不出原因。
     * </p>
     * <p>
     * 这里是全流程中<b>唯一</b>能确切知道包内含哪些集的时刻——下载器已经解析完元数据、
     * 给出了完整文件列表。判据既然精确，就该在这里一次性把账做平。
     * </p>
     * <p>
     * 三条保守约束，宁可少退也不能错退：
     * <ul>
     *   <li>{@code actualEpisodes} 为空时什么都不做——那说明文件名一个集号都没解析出来
     *       （整季打包成单文件、命名奇特），不是"包里没有这些集"的证据。</li>
     *   <li>电影订阅整体跳过：电影的集号是哨兵值 0，而文件名里随便一个数字都可能被解析成集号，
     *       比对必然假阳性，会把刚推送的电影退回缺失。</li>
     *   <li>只退 IN_FLIGHT 的集（条件更新兜住）：洗版占位的是 UPGRADING，它的旧文件一直在库里，
     *       退成 MISSING 会让这一集显示成缺失并被从头重下，比不洗版还糟。</li>
     * </ul>
     * 也<b>不累加 {@code fail_count}</b>：这不是"这一集补不到货"，是占位范围估错了，
     * 累加会让几次季包误占把一个正常的集熔断成 BLOCKED。
     * </p>
     */
    private void reconcileClaims(PtDownloadRecordPlus record, PtSubscriptionPlus sub,
                                 List<PtSubscriptionEpisodePlus> targets, Set<Integer> actualEpisodes) {
        if (actualEpisodes.isEmpty() || sub == null || TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return;
        }
        // 对账的另一半：包内确实有的集打上"文件已确认"。同一份 actualEpisodes 判据，
        // 一半用来退多占的集，一半用来保护下好了的集，见 markFileConfirmed 的说明
        markFileConfirmed(targets, actualEpisodes);
        List<PtSubscriptionEpisodePlus> orphans = targets.stream()
                .filter(ep -> EP_IN_FLIGHT.equals(ep.getState()))
                .filter(ep -> ep.getEpisode() != null && !actualEpisodes.contains(ep.getEpisode()))
                .toList();
        if (orphans.isEmpty()) {
            return;
        }
        int released = 0;
        for (PtSubscriptionEpisodePlus episode : orphans) {
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(EP_MISSING);
            set.setDownloadId(null);
            boolean changed = episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", episode.getId())
                    .eq("state", EP_IN_FLIGHT)
                    // 实体里的 null 会被 MyBatis-Plus 跳过，download_id 必须显式置空，
                    // 否则退回缺失的集仍指着这条记录，下次追踪又会把它当成本记录的目标
                    .set("download_id", null));
            if (changed) {
                released++;
            }
        }
        if (released == 0) {
            return;
        }
        String episodeList = orphans.stream()
                .map(PtSubscriptionEpisodePlus::getEpisode)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining("、"));
        log.info("下载记录[{}] 实际只含 {} 集，多占的 {} 个集已退回缺失（第 {} 集）：{}",
                record.getId(), actualEpisodes.size(), released, episodeList, record.getTitle());
        notifySafely(NotificationType.SUBSCRIPTION_HIT, "📦 季包实际不含全季：《"
                + StringUtils.escapeHtml(sub.getTitle()) + "》\n"
                + StringUtils.escapeHtml(record.getTitle())
                + "\n包内实际 " + actualEpisodes.size() + " 集，多占的第 " + episodeList
                + " 集已退回缺失，将继续自动搜索补齐", sub.getOwnerUserId());
    }

    /**
     * 判完成前补一次文件对账，只为给集打确认标记。
     * <p>
     * <b>为什么不能只靠 {@link #trySelectFiles}。</b>那个方法在 {@code trackActive} 的
     * "未完成"分支里，只有轮询至少撞见过一次"还在下载"才会跑。而秒下、已在本地做种被重新
     * 加回、或体积很小的单集，完全可能在两次 30 秒轮询之间下完——第一次看见它就已经是
     * completed，于是文件列表一次都没读过，{@code file_confirmed} 恒为 0。那样的集一旦
     * 上传慢，12 小时后就会被清扫误判成卡死重下，正是本标记要防的事。
     * </p>
     * <p>
     * 这里刻意<b>不</b>做排除文件与启动种子：都已经下完了，排除文件毫无意义，启动更是空操作。
     * 只读一次列表、打标、顺带把多占的集退回（快速完成的季包同样会多占）。
     * </p>
     * <p>
     * 全程异常自吞：这只是一个让日后清扫更准的补充信息，绝不能让它挡住 {@code complete()}——
     * 下载确实成功了，通知、H&R 追踪、STRM 联动都必须照常发生。
     * </p>
     */
    private void confirmEpisodesIfNeeded(PtDownloaderPlus downloader, PtDownloadRecordPlus record,
                                         DownloaderTorrent matched, PtSubscriptionPlus sub) {
        if (Boolean.TRUE.equals(record.getFilesSelected())) {
            // trySelectFiles 已经跑过完整对账，标记也打过了
            return;
        }
        if (sub == null || TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return;
        }
        try {
            List<PtSubscriptionEpisodePlus> targets = episodeService.list(
                    new QueryWrapper<PtSubscriptionEpisodePlus>().eq("download_id", record.getId()));
            if (targets.isEmpty()) {
                return;
            }
            List<DownloaderTorrentFile> files = downloaderClientFactory.get(downloader)
                    .listFiles(downloader, matched.getHash());
            Set<Integer> actualEpisodes = new HashSet<>();
            for (DownloaderTorrentFile file : files) {
                Integer episode = toInt(mediaParser.parseLocal(file.getName()).getEpisode());
                if (episode != null) {
                    actualEpisodes.add(episode);
                }
            }
            reconcileClaims(record, sub, targets, actualEpisodes);
        } catch (Exception e) {
            log.warn("下载记录[{}] 完成前补对账文件列表失败，该集将按未确认处理：{}", record.getId(), e.getMessage());
        }
    }

    /**
     * 给「包内确实存在」的集打上 {@code file_confirmed=1}。
     * <p>
     * 这是 {@link StuckEpisodeSweepService} 唯一能分开两种长期在途的依据：
     * </p>
     * <ul>
     *   <li><b>文件根本不在种子里</b>（季包多占）——上面的 orphans 分支已经把它们退回缺失了，
     *       走不到这里，{@code file_confirmed} 保持 0，日后真要卡死也该重搜。</li>
     *   <li><b>文件已经下好，只是还没传上网盘</b>——网盘秒传要等别人先传过同一份文件，
     *       否则只能真传，大文件跨天、中途失败重来都是常态（失败会落进 {@code openlist_copy_record}，
     *       可在复制记录页重试）。这种集在 Emby 里同样查不到，症状与前一种一模一样，
     *       但重下一遍解决不了任何问题：本地文件本来就在，白费带宽还多背一份 H&R 保种义务，
     *       而且每轮清扫都累加 {@code fail_count}，三轮之后把一个好端端的集熔断成 BLOCKED。</li>
     * </ul>
     * <p>
     * 一条 UPDATE 批量打标而不是逐条：一个季包能一次确认几十集。条件里带 {@code state=IN_FLIGHT}
     * 与其余回退路径同源——洗版占位的 UPGRADING 集不该被这里动到。
     * </p>
     */
    private void markFileConfirmed(List<PtSubscriptionEpisodePlus> targets, Set<Integer> actualEpisodes) {
        List<Integer> ids = targets.stream()
                .filter(ep -> EP_IN_FLIGHT.equals(ep.getState()))
                .filter(ep -> ep.getEpisode() != null && actualEpisodes.contains(ep.getEpisode()))
                .filter(ep -> !FILE_CONFIRMED.equals(ep.getFileConfirmed()))
                .map(PtSubscriptionEpisodePlus::getId)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
        set.setFileConfirmed(FILE_CONFIRMED);
        episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                .in("id", ids)
                .eq("state", EP_IN_FLIGHT));
        log.debug("下载记录关联的 {} 个集已确认文件在种子内，卡死清扫将不再退回它们", ids.size());
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
    private void notifySafely(String msg, Long ownerUserId) {
        notifySafely(NotificationType.GENERAL, msg, ownerUserId);
    }

    /**
     * 发通知给订阅归属人。{@code ownerUserId} 为 null 时退化为广播（无归属的历史订阅、
     * 或订阅已被删除），支持分人投递的渠道据此决定发给谁。
     */
    private void notifySafely(NotificationType type, String msg, Long ownerUserId) {
        try {
            TgHelper.sendMsg(type, msg, NotifyTarget.owner(ownerUserId));
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /**
     * 从下载记录反查订阅归属人。订阅已被删除时返回 null（按广播处理）——
     * 记录还在但订阅没了是可能的，不能因此让通知整条丢掉。
     */
    private Long ownerOf(PtDownloadRecordPlus record) {
        if (record == null || record.getSubId() == null) {
            return null;
        }
        PtSubscriptionPlus sub = subscriptionService.getById(record.getSubId());
        return sub == null ? null : sub.getOwnerUserId();
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
        boolean upgraded = finishUpgrade(record);
        notifySafely(NotificationType.DOWNLOAD_COMPLETE, "✅ " + (upgraded ? "洗版" : "") + "下载完成："
                + StringUtils.escapeHtml(record.getTitle())
                + (upgraded ? "\n⚠️ 旧版本不会被自动删除，请自行清理，否则媒体库里会出现同一集的两个版本" : "")
                + (hitAndRun ? "\n🌱 该站点有 H&R 考核，需保种至「" + StringUtils.escapeHtml(describeRequirement(indexer))
                        + "」，达标前请勿删除" : ""), ownerOf(record));
        log.info("下载记录[{}] 已完成：{}{}", record.getId(), record.getTitle(),
                hitAndRun ? "（进入 H&R 保种考核）" : "");
        // 补缺集时集状态不动，仍是 IN_FLIGHT，等 Emby 对账确认入库（洗版则已在 finishUpgrade 收尾）；
        // 下载器关联了 STRM 任务时异步触发一次增量生成+提前对账，没关联时纯靠 LibrarySyncTask 下一轮兜底
        completionSyncTrigger.triggerAsync(record, downloader);
    }

    /**
     * 洗版下载完成的收尾：把 UPGRADING 的集转回 IN_LIBRARY，并用新种子的标题刷新质量基线。
     * <p>
     * <b>这个转换只能由下载完成驱动，不能交给 Emby 对账。</b>
     * {@code SubscriptionService#refresh} 判断"在不在库里"靠的是 Emby 查询，而旧版本本来就在库里，
     * 查询恒命中——对账无从分辨同一集的新旧版本，顺着走会在洗版还没下完时就把状态改掉。
     * 因此 refresh 那边刻意跳过 UPGRADING，收尾的责任落在这里。
     * </p>
     * <p>
     * 质量基线必须同步刷新：不刷的话下一轮扫描仍按旧画像判断，会把刚下好的这个版本
     * 再当成"可升级"，反复洗同一集。
     * </p>
     *
     * @return 这次完成的是否是一个洗版下载
     */
    private boolean finishUpgrade(PtDownloadRecordPlus record) {
        // 状态条件同时写在 SQL 与内存过滤里：SQL 那份是为了少捞行，内存这份是为了让
        // "只处理 UPGRADING" 这个前提在代码里看得见，不必读 wrapper 才知道
        List<PtSubscriptionEpisodePlus> upgrading = episodeService.list(
                new QueryWrapper<PtSubscriptionEpisodePlus>()
                        .eq("download_id", record.getId())
                        .eq("state", EP_UPGRADING))
                .stream()
                .filter(e -> EP_UPGRADING.equals(e.getState()))
                .toList();
        if (upgrading.isEmpty()) {
            return false;
        }
        String quality = null;
        try {
            quality = QualityProfile.from(mediaParser.parseLocal(record.getTitle())).toJson();
        } catch (Exception e) {
            // 画像解析失败不该阻断状态收尾，否则集会永久卡在 UPGRADING。
            // 基线留空 → 下一轮扫描把它标成 NO_BASELINE，不再参与洗版，是安全的降级
            log.warn("下载记录[{}] 洗版完成后解析质量画像失败，该集将按无基线处理：{}",
                    record.getId(), e.getMessage());
        }
        for (PtSubscriptionEpisodePlus episode : upgrading) {
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(EP_IN_LIBRARY);
            set.setQuality(quality);
            // 置回 PENDING 而不是 REACHED：新版本是不是已经够好由扫描任务用 UpgradeCriteria 评估，
            // 追踪这条路径不该为此再读一份洗版配置
            set.setUpgradeState(quality == null
                    ? UpgradeState.NO_BASELINE.value() : UpgradeState.PENDING.value());
            episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", episode.getId())
                    .eq("state", EP_UPGRADING));
        }
        log.info("下载记录[{}] 洗版完成，{} 个集已更新质量基线：{}",
                record.getId(), upgrading.size(), record.getTitle());
        return true;
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
        doFail(record, code, reason, true, null);
    }

    /**
     * {@link #fail} 的完全体，供需要差异化收尾的失败路径调用。
     *
     * @param countFailure 是否给回退的集累加连续失败次数。"下载没成功"要累加（熔断掉永远
     *                     补不到的集）；"占位范围估错了"不该累加（见 {@link #abortNoTargetEpisode}）
     * @param notice       自定义通知文案；{@code null} 时用默认的"下载失败/洗版失败"文案
     */
    private void doFail(PtDownloadRecordPlus record, FailReasonCode code, String reason,
                        boolean countFailure, String notice) {
        // 1) 先回退关联集（幂等：只动 IN_FLIGHT / UPGRADING 的；普通集1条、季包多条统一处理）。
        // 一次查出两类在途集再按状态分流，而不是分两条查询：补缺集与洗版的回退目标不同，
        // 但"这条下载记录关联着哪些还没落定的集"是同一个问题，查两次既多一次往返，
        // 也让"同一集同时出现在两个结果里"这种不可能的状态在代码里变得可表达。
        List<PtSubscriptionEpisodePlus> pending = episodeService.list(
                new QueryWrapper<PtSubscriptionEpisodePlus>()
                        .eq("download_id", record.getId())
                        .in("state", EP_IN_FLIGHT, EP_UPGRADING));
        Rollback rollback = releaseInFlightEpisodes(pending, countFailure);
        int upgradeReverted = revertUpgradingEpisodes(pending);
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
        notifySafely(NotificationType.DOWNLOAD_FAILED, notice != null ? notice : (upgradeReverted > 0
                ? "❌ 洗版下载失败：" + StringUtils.escapeHtml(record.getTitle()) + "，原有版本保持不变"
                : "❌ 下载失败：" + StringUtils.escapeHtml(record.getTitle()) + "，已释放待下轮重新匹配"), ownerOf(record));
        log.warn("下载记录[{}] 失败（{} 个集回退缺失，{} 个集回退入库）：{}",
                record.getId(), rollback.released(), upgradeReverted, record.getTitle());
        if (rollback.blocked() > 0) {
            notifySafely("🚫 " + StringUtils.escapeHtml(record.getTitle()) + " 连续失败达 " + maxConsecutiveFailures
                    + " 次，已停止自动重试，需到下载记录管理页人工重试", ownerOf(record));
        }
    }

    /** 回退结果：released=回退的集数，blocked=其中因连续失败达阈值而熔断的集数 */
    private record Rollback(int released, int blocked) {
    }

    /**
     * 回退补缺集下载关联的集：达到熔断阈值前退回 MISSING（RSS/补搜会重新捡回），
     * 达到阈值后转 BLOCKED 停止自动重试，避免已下架/失效的资源被无限次静默重试。
     */
    private Rollback releaseInFlightEpisodes(List<PtSubscriptionEpisodePlus> pending, boolean countFailure) {
        List<PtSubscriptionEpisodePlus> episodes = pending.stream()
                .filter(e -> EP_IN_FLIGHT.equals(e.getState()))
                .toList();
        int blocked = 0;
        for (PtSubscriptionEpisodePlus episode : episodes) {
            int fails = (episode.getFailCount() == null ? 0 : episode.getFailCount()) + 1;
            boolean cut = countFailure && fails >= maxConsecutiveFailures;
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(cut ? EP_BLOCKED : EP_MISSING);
            // 不累加时把 fail_count 留空：实体里的 null 会被 MyBatis-Plus 跳过，
            // 正好等于"这一次不计入熔断，原有计数保持不变"
            if (countFailure) {
                set.setFailCount(fails);
            }
            episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", episode.getId())
                    .eq("state", EP_IN_FLIGHT)
                    // 实体的 null 同样会被跳过，download_id 必须走 UpdateWrapper 显式置空
                    // （口径与 reconcileClaims、StuckEpisodeSweepService 一致），否则退回缺失的集
                    // 仍指着这条 FAILED 记录，用户在下载记录页手动重试它时会把它们又拖回在途
                    .set("download_id", null));
            if (cut) {
                blocked++;
            }
        }
        return new Rollback(episodes.size(), blocked);
    }

    /**
     * 回退洗版下载关联的集：UPGRADING → IN_LIBRARY。
     * <p>
     * <b>不能退成 MISSING</b>：旧版本的文件一直好端端在库里，退成 MISSING 会让这一集
     * 显示成缺失并被 RSS 从头重下一遍，比不洗版还糟。
     * </p>
     * <p>
     * <b>也不累加 fail_count</b>：那个计数是"这一集补不到货"的熔断依据，
     * 累加它会让几次洗版失败把一个明明已入库的集熔断成 BLOCKED。
     * 质量基线保持不变，下一轮扫描会重新评估、换个候选再试。
     * </p>
     *
     * @return 回退的集数
     */
    private int revertUpgradingEpisodes(List<PtSubscriptionEpisodePlus> pending) {
        List<PtSubscriptionEpisodePlus> episodes = pending.stream()
                .filter(e -> EP_UPGRADING.equals(e.getState()))
                .toList();
        for (PtSubscriptionEpisodePlus episode : episodes) {
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(EP_IN_LIBRARY);
            episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", episode.getId())
                    .eq("state", EP_UPGRADING)
                    // 同 releaseInFlightEpisodes：实体的 null 会被 MyBatis-Plus 跳过，
                    // download_id 必须走 UpdateWrapper 才真的能置空
                    .set("download_id", null));
        }
        return episodes.size();
    }
}
