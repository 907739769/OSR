package com.osr.openliststrm.pt.health;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubtitleHealthServiceTest {

    private final IPtSubscriptionPlusService subscriptionService = mock(IPtSubscriptionPlusService.class);
    private final IPtSubscriptionEpisodePlusService episodeService = mock(IPtSubscriptionEpisodePlusService.class);
    private final IPtDownloadRecordPlusService recordService = mock(IPtDownloadRecordPlusService.class);
    private final SubtitleHealthService service = new SubtitleHealthService(subscriptionService, episodeService, recordService);

    private static PtSubscriptionPlus sub(int id, String originalTitle) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setTitle("剧" + id);
        s.setOriginalTitle(originalTitle);
        s.setMediaType("TV");
        s.setSeason(1);
        return s;
    }

    private static PtSubscriptionEpisodePlus ep(int subId, int no, int downloadId) {
        PtSubscriptionEpisodePlus e = new PtSubscriptionEpisodePlus();
        e.setSubId(subId);
        e.setEpisode(no);
        e.setState("IN_LIBRARY");
        e.setDownloadId(downloadId);
        return e;
    }

    private static PtDownloadRecordPlus record(int id, String title, String subtitle) {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(id);
        r.setTitle(title);
        r.setSubtitle(subtitle);
        return r;
    }

    @Test
    void 华语作品跳过_日韩作品即便含汉字也不跳过() {
        assertTrue(SubtitleHealthService.isChineseOrigin(sub(1, "三体")));
        assertFalse(SubtitleHealthService.isChineseOrigin(sub(2, "進撃の巨人")), "有假名的是日语");
        assertFalse(SubtitleHealthService.isChineseOrigin(sub(3, "오징어 게임")));
        assertFalse(SubtitleHealthService.isChineseOrigin(sub(4, "The Wire")));
        assertFalse(SubtitleHealthService.isChineseOrigin(sub(5, null)), "原名缺失时宁可多报");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 按下载记录判定_没记录的旧记录按标题现算并注明() {
        when(subscriptionService.list()).thenReturn(List.of(sub(1, "The Wire")));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(ep(1, 1, 10), ep(1, 2, 11), ep(1, 3, 12)));
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(
                record(10, "The.Wire.S01E01", "ZH"),
                record(11, "The.Wire.S01E02", "OTHER"),
                record(12, "The.Wire.S01E03.1080p", null)));

        List<SubtitleHealthService.SubtitleIssue> issues = service.report(s -> true);

        assertEquals(1, issues.size());
        assertEquals(List.of(3), issues.get(0).noChinese());
        assertEquals(List.of(2), issues.get(0).unknownLanguage());
        assertTrue(issues.get(0).titleOnly());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 看不到的订阅与华语订阅_不查集表() {
        when(subscriptionService.list()).thenReturn(List.of(sub(1, "三体")));

        assertTrue(service.report(s -> true).isEmpty());
        verify(episodeService, never()).list(any(Wrapper.class));
    }
}
