package com.osr.openliststrm.pt.stats;

import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSeedSnapshotPlus;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 保种看板：每日上传量按累计计数器求差，删种与计数器清零都不能画成负数；两张列表的筛选与排序。
 */
class SeedingStatsServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);

    private static PtSeedSnapshotPlus snap(int downloader, LocalDate date, Long cumulative, long uploadedSum, long seedingSize) {
        PtSeedSnapshotPlus s = new PtSeedSnapshotPlus();
        s.setDownloaderId(downloader);
        s.setSnapshotDate(date);
        s.setCumulativeUploaded(cumulative);
        s.setUploadedSum(uploadedSum);
        s.setSeedingSize(seedingSize);
        return s;
    }

    @Test
    void 上传量按计数器求差_多台相加_缺前一天为空() {
        List<SeedingStatsService.DayPoint> points = SeedingStatsService.trend(List.of(
                snap(1, D1, 100L, 0, 10), snap(1, D1.plusDays(1), 150L, 0, 11),
                snap(2, D1, 1000L, 0, 20), snap(2, D1.plusDays(1), 1030L, 0, 21)), D1, D1.plusDays(1));

        assertNull(points.get(0).uploaded(), "第一天没有前一天可比");
        assertEquals(30, points.get(0).seedingSize());
        assertEquals(80L, points.get(1).uploaded());
        assertEquals(32, points.get(1).seedingSize());
    }

    @Test
    void 计数器清零那天不算_计数器缺失时用现存种子上传和兜底_删种变小也不算() {
        List<SeedingStatsService.DayPoint> reset = SeedingStatsService.trend(List.of(
                snap(1, D1, 500L, 0, 0), snap(1, D1.plusDays(1), 20L, 0, 0)), D1.plusDays(1), D1.plusDays(1));
        assertNull(reset.get(0).uploaded());

        List<SeedingStatsService.DayPoint> fallback = SeedingStatsService.trend(List.of(
                snap(1, D1, null, 300, 0), snap(1, D1.plusDays(1), null, 340, 0),
                snap(1, D1.plusDays(2), null, 100, 0)), D1.plusDays(1), D1.plusDays(2));
        assertEquals(40L, fallback.get(0).uploaded());
        assertNull(fallback.get(1).uploaded(), "删了种导致求和变小");
    }

    @Test
    void 现状汇总只把下完的算作保种() {
        DownloaderTorrent done = torrent(1.0, 100, 50);
        DownloaderTorrent half = torrent(0.5, 200, 10);
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(3);
        d.setName("qb");

        SeedingStatsService.DownloaderSeeding s = SeedingStatsService.summarize(d, List.of(done, half), 999L);

        assertEquals(2, s.torrentCount());
        assertEquals(1, s.seedingCount());
        assertEquals(100, s.seedingSize());
        assertEquals(60, s.uploadedSum());
        assertEquals(999L, s.cumulativeUploaded());
    }

    @Test
    void 低效保种_满7天且分享率低_按体积排() {
        List<SeedingStatsService.TorrentRow> rows = List.of(
                new SeedingStatsService.TorrentRow("小", "qb", 10, 0, 0.0, 30),
                new SeedingStatsService.TorrentRow("大", "qb", 100, 1, 0.01, 8),
                new SeedingStatsService.TorrentRow("刚下完", "qb", 500, 0, 0.0, 2),
                new SeedingStatsService.TorrentRow("分享率够", "qb", 300, 600, 2.0, 30));

        assertEquals(List.of("大", "小"), SeedingStatsService.lowRatio(rows).stream().map(SeedingStatsService.TorrentRow::name).toList());
        assertEquals(List.of("分享率够", "大"), SeedingStatsService.topUploaded(rows).stream().map(SeedingStatsService.TorrentRow::name).toList());
    }

    private static DownloaderTorrent torrent(double progress, long size, long uploaded) {
        DownloaderTorrent t = new DownloaderTorrent();
        t.setProgress(progress);
        t.setSize(size);
        t.setUploaded(uploaded);
        return t;
    }
}
