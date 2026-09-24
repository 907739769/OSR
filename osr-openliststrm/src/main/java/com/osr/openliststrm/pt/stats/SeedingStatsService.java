package com.osr.openliststrm.pt.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.FaultThrottle;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSeedSnapshotPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSeedSnapshotPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保种与上传量看板：各下载器的保种现状（实时）+ 每日上传量与保种体积趋势（来自 {@code pt_seed_snapshot}）。
 * <p>
 * 数据只来自下载器本身，与订阅无关，因此<b>只给管理员看</b>——一台下载器里是全家所有人的种子，
 * 不存在按归属拆分的口径。
 * </p>
 * <p>
 * <b>每日上传量按下载器的累计计数器求差</b>（{@link IDownloaderClient#cumulativeUploaded}），不按现存种子的
 * {@code uploaded} 求和：后者删一个种子那天就是负数。计数器变小（下载器重装、统计被清零）的那一天
 * 当作取不到，不画成负数。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class SeedingStatsService {

    /** 「低效保种」：已做种这么多天、分享率仍低于阈值的，才值得考虑腾地方 */
    static final long LOW_RATIO_MIN_SEEDING_DAYS = 7;
    static final double LOW_RATIO_THRESHOLD = 0.2;
    static final int LIST_LIMIT = 10;

    private final IPtDownloaderPlusService downloaderService;
    private final DownloaderClientFactory clientFactory;
    private final IPtSeedSnapshotPlusService snapshotService;
    private final FaultThrottle faults = new FaultThrottle();

    public SeedingStatsService(IPtDownloaderPlusService downloaderService, DownloaderClientFactory clientFactory,
                               IPtSeedSnapshotPlusService snapshotService) {
        this.downloaderService = downloaderService;
        this.clientFactory = clientFactory;
        this.snapshotService = snapshotService;
    }

    /** 一台下载器此刻的保种现状；连不上时 error 有值、其余为 0 */
    public record DownloaderSeeding(Integer id, String name, String error, int torrentCount, int seedingCount,
                                    long seedingSize, long uploadedSum, Long cumulativeUploaded) {
    }

    /** 列表里的一个种子 */
    public record TorrentRow(String name, String downloader, long size, long uploaded, double ratio, long seedingDays) {
    }

    /** 趋势上的一天；uploaded 为 null 表示那天算不出（缺前一天快照、或计数器被清零） */
    public record DayPoint(String date, Long uploaded, long seedingSize) {
    }

    public record Overview(List<DownloaderSeeding> downloaders, List<DayPoint> trend,
                           List<TorrentRow> topUploaded, List<TorrentRow> lowRatio) {
    }

    /** 某台下载器此刻的原始数据：汇总 + 全部种子 */
    record Live(DownloaderSeeding summary, List<DownloaderTorrent> torrents) {
    }

    public Overview overview(int days) {
        List<DownloaderSeeding> summaries = new ArrayList<>();
        List<TorrentRow> rows = new ArrayList<>();
        for (PtDownloaderPlus d : enabledDownloaders()) {
            Live live = fetch(d);
            summaries.add(live.summary());
            for (DownloaderTorrent t : live.torrents()) {
                rows.add(new TorrentRow(t.getName(), d.getName(), t.getSize(), t.getUploaded(), t.getRatio(),
                        t.getSeedingSeconds() / 86400));
            }
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1L);
        List<PtSeedSnapshotPlus> snapshots = snapshotService.list(new LambdaQueryWrapper<PtSeedSnapshotPlus>()
                // 多取前一天：第一天的上传量要拿它求差
                .ge(PtSeedSnapshotPlus::getSnapshotDate, start.minusDays(1)));
        return new Overview(summaries, trend(snapshots, start, end), topUploaded(rows), lowRatio(rows));
    }

    /** 每小时由 {@code SeedSnapshotTask} 调用：覆盖每台下载器今天那一行。返回成功写入的台数 */
    public int snapshotAll() {
        LocalDate today = LocalDate.now();
        int written = 0;
        for (PtDownloaderPlus d : enabledDownloaders()) {
            Live live = fetch(d);
            if (live.summary().error() != null) {
                continue;
            }
            upsert(d.getId(), today, live.summary());
            written++;
        }
        return written;
    }

    private List<PtDownloaderPlus> enabledDownloaders() {
        return downloaderService.list(new LambdaQueryWrapper<PtDownloaderPlus>()
                .eq(PtDownloaderPlus::getEnabled, "1").orderByAsc(PtDownloaderPlus::getId));
    }

    Live fetch(PtDownloaderPlus d) {
        IDownloaderClient client = clientFactory.get(d);
        try {
            List<DownloaderTorrent> torrents = client.listAll(d);
            Long cumulative = null;
            try {
                cumulative = client.cumulativeUploaded(d);
            } catch (Exception e) {
                // 累计计数器取不到不影响现状统计，趋势那天按兜底口径算
                log.debug("下载器[{}] 累计上传量取不到：{}", d.getName(), e.getMessage());
            }
            if (faults.onSuccess("dl:" + d.getId())) {
                log.info("保种看板：下载器[{}] 已恢复连接", d.getName());
            }
            return new Live(summarize(d, torrents, cumulative), torrents);
        } catch (Exception e) {
            FaultThrottle.Decision decision = faults.onFailure("dl:" + d.getId());
            if (decision.shouldReport()) {
                log.warn("保种看板：下载器[{}] 连不上（连续 {} 次）：{}", d.getName(), decision.consecutiveFailures(), e.getMessage());
            }
            return new Live(new DownloaderSeeding(d.getId(), d.getName(), e.getMessage(), 0, 0, 0, 0, null), List.of());
        }
    }

    static DownloaderSeeding summarize(PtDownloaderPlus d, List<DownloaderTorrent> torrents, Long cumulative) {
        int seeding = 0;
        long seedingSize = 0;
        long uploaded = 0;
        for (DownloaderTorrent t : torrents) {
            uploaded += t.getUploaded();
            if (t.getProgress() >= 1.0) {
                seeding++;
                seedingSize += t.getSize();
            }
        }
        return new DownloaderSeeding(d.getId(), d.getName(), null, torrents.size(), seeding, seedingSize, uploaded, cumulative);
    }

    private void upsert(Integer downloaderId, LocalDate date, DownloaderSeeding s) {
        PtSeedSnapshotPlus row = snapshotService.getOne(new LambdaQueryWrapper<PtSeedSnapshotPlus>()
                .eq(PtSeedSnapshotPlus::getDownloaderId, downloaderId)
                .eq(PtSeedSnapshotPlus::getSnapshotDate, date), false);
        if (row == null) {
            row = new PtSeedSnapshotPlus();
            row.setDownloaderId(downloaderId);
            row.setSnapshotDate(date);
        }
        row.setTorrentCount(s.torrentCount());
        row.setSeedingCount(s.seedingCount());
        row.setSeedingSize(s.seedingSize());
        row.setUploadedSum(s.uploadedSum());
        row.setCumulativeUploaded(s.cumulativeUploaded());
        row.setUpdateTime(new Date());
        snapshotService.saveOrUpdate(row);
    }

    /**
     * 按天汇总全部下载器：保种体积直接相加；上传量是每台「当天 − 前一天」之和。
     * 某台缺前一天快照、或计数器变小（被清零）时，这台当天不计入；全部都算不出时那天为 null。
     * 计数器取不到的下载器按 {@code uploaded_sum} 求差兜底，差为负（删过种）同样不计入。
     */
    static List<DayPoint> trend(List<PtSeedSnapshotPlus> snapshots, LocalDate start, LocalDate end) {
        Map<Integer, Map<LocalDate, PtSeedSnapshotPlus>> byDownloader = new HashMap<>();
        for (PtSeedSnapshotPlus s : snapshots) {
            byDownloader.computeIfAbsent(s.getDownloaderId(), k -> new HashMap<>()).put(s.getSnapshotDate(), s);
        }
        List<DayPoint> points = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Long uploaded = null;
            long seedingSize = 0;
            for (Map<LocalDate, PtSeedSnapshotPlus> rows : byDownloader.values()) {
                PtSeedSnapshotPlus today = rows.get(day);
                if (today == null) {
                    continue;
                }
                seedingSize += today.getSeedingSize() == null ? 0 : today.getSeedingSize();
                Long delta = delta(rows.get(day.minusDays(1)), today);
                if (delta != null) {
                    uploaded = (uploaded == null ? 0 : uploaded) + delta;
                }
            }
            points.add(new DayPoint(day.toString(), uploaded, seedingSize));
        }
        return points;
    }

    private static Long delta(PtSeedSnapshotPlus prev, PtSeedSnapshotPlus today) {
        if (prev == null) {
            return null;
        }
        Long a = prev.getCumulativeUploaded();
        Long b = today.getCumulativeUploaded();
        if (a == null || b == null) {
            a = prev.getUploadedSum();
            b = today.getUploadedSum();
        }
        if (a == null || b == null || b < a) {
            return null;
        }
        return b - a;
    }

    static List<TorrentRow> topUploaded(List<TorrentRow> rows) {
        return rows.stream().filter(r -> r.uploaded() > 0)
                .sorted(Comparator.comparingLong(TorrentRow::uploaded).reversed())
                .limit(LIST_LIMIT).toList();
    }

    /** 做种满 7 天、分享率仍低于 0.2 的，按体积从大到小：占地方最多、贡献最少的排前面 */
    static List<TorrentRow> lowRatio(List<TorrentRow> rows) {
        return rows.stream()
                .filter(r -> r.seedingDays() >= LOW_RATIO_MIN_SEEDING_DAYS && r.ratio() < LOW_RATIO_THRESHOLD)
                .sorted(Comparator.comparingLong(TorrentRow::size).reversed())
                .limit(LIST_LIMIT).toList();
    }
}
