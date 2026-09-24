package com.osr.openliststrm.service;

import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmDirSnapshotPlus;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrmIncrementalScanTest {

    private static final Date NOW = new Date(1_800_000_000_000L);

    private static Date daysAgo(double days) {
        return new Date(NOW.getTime() - (long) (days * TimeUnit.DAYS.toMillis(1)));
    }

    private static OpenlistStrmDirSnapshotPlus snapshot(String modified, String sign) {
        OpenlistStrmDirSnapshotPlus s = new OpenlistStrmDirSnapshotPlus();
        s.setModified(modified);
        s.setSettingsSign(sign);
        return s;
    }

    @Test
    void 从没全量过_必定全量() {
        assertTrue(StrmIncrementalScan.fullScanDue(null, 7, NOW));
    }

    @Test
    void 全量间隔内_走增量_到期_走全量() {
        assertFalse(StrmIncrementalScan.fullScanDue(daysAgo(6.9), 7, NOW));
        assertTrue(StrmIncrementalScan.fullScanDue(daysAgo(7), 7, NOW));
    }

    @Test
    void 间隔配成0_每次都全量() {
        assertTrue(StrmIncrementalScan.fullScanDue(daysAgo(0.01), 0, NOW));
    }

    @Test
    void 快照与修改时间和设置都对得上_才跳过() {
        assertTrue(StrmIncrementalScan.canSkip(snapshot("2026-09-01T10:00:00+08:00", "s"), "2026-09-01T10:00:00+08:00", "s"));
        assertFalse(StrmIncrementalScan.canSkip(null, "2026-09-01T10:00:00+08:00", "s"), "没有快照");
        assertFalse(StrmIncrementalScan.canSkip(snapshot("2026-09-01T10:00:00+08:00", "s"), "2026-09-02T10:00:00+08:00", "s"), "目录变了");
        assertFalse(StrmIncrementalScan.canSkip(snapshot("2026-09-01T10:00:00+08:00", "s"), "2026-09-01T10:00:00+08:00", "s2"), "设置变了");
    }

    /** 有的驱动对目录一律返回 Go 的零值时间；拿它做快照，目录会被永远跳过 */
    @Test
    void 空值与零值时间_视为不可用_永不跳过() {
        assertFalse(StrmIncrementalScan.knownModified(null));
        assertFalse(StrmIncrementalScan.knownModified(""));
        assertFalse(StrmIncrementalScan.knownModified("0001-01-01T00:00:00Z"));
        assertFalse(StrmIncrementalScan.canSkip(snapshot("0001-01-01T00:00:00Z", "s"), "0001-01-01T00:00:00Z", "s"));
    }

    @Test
    void 设置指纹_扩展名顺序无关_其他任一项变了就变() {
        Set<String> video = new LinkedHashSet<>(List.of("mkv", "mp4"));
        Set<String> videoReordered = new LinkedHashSet<>(List.of("mp4", "mkv"));
        String base = StrmIncrementalScan.settingsSign("http://ol", false, true, 100, "/strm", video, Set.of("srt"));

        assertEquals(base, StrmIncrementalScan.settingsSign("http://ol", false, true, 100, "/strm", videoReordered, Set.of("srt")));
        assertNotEquals(base, StrmIncrementalScan.settingsSign("http://ol", false, true, 50, "/strm", video, Set.of("srt")));
        assertNotEquals(base, StrmIncrementalScan.settingsSign("http://ol", false, false, 100, "/strm", video, Set.of("srt")));
        assertNotEquals(base, StrmIncrementalScan.settingsSign("http://ol", false, true, 100, "/strm", Set.of("mkv"), Set.of("srt")));
    }
}
