package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 一键诊断里判错了不报错、只会把人往错方向引的几条：只看最近一轮、季包日志算到每一集、
 * 未播出的不诊断、订阅级的前提问题先说。
 */
class SubscriptionDiagnosisServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private static final long NOW = Date.from(TODAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()).getTime();

    private final IPtSubscriptionPlusService subscriptionService = mock(IPtSubscriptionPlusService.class);
    private final IPtSubscriptionEpisodePlusService episodeService = mock(IPtSubscriptionEpisodePlusService.class);
    private final IPtSearchLogPlusService logService = mock(IPtSearchLogPlusService.class);
    private final IPtIndexerPlusService indexerService = mock(IPtIndexerPlusService.class);
    private final SubscriptionDiagnosisService service =
            new SubscriptionDiagnosisService(subscriptionService, episodeService, logService, indexerService);

    private final List<PtSearchLogPlus> logs = new ArrayList<>();
    private final List<PtSubscriptionEpisodePlus> episodes = new ArrayList<>();
    private PtSubscriptionPlus sub;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sub = new PtSubscriptionPlus();
        sub.setId(1);
        sub.setTitle("三体");
        sub.setMediaType("TV");
        sub.setStatus(SubscriptionService.STATUS_ACTIVE);
        sub.setAutoSearch("1");
        when(subscriptionService.getById(1)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(new PtIndexerPlus()));
        when(episodeService.list(any(Wrapper.class))).thenReturn(episodes);
        when(logService.list(any(Wrapper.class))).thenReturn(logs);
    }

    private void episode(int no, String state, LocalDate airDate) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setSubId(1);
        ep.setEpisode(no);
        ep.setState(state);
        ep.setAirDate(airDate == null ? null : Date.from(airDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        episodes.add(ep);
    }

    private void log(int episode, long minutesAgo, String title, String accepted, String code) {
        PtSearchLogPlus l = new PtSearchLogPlus();
        l.setSubId(1);
        l.setEpisode(episode);
        l.setSource("SUPPLEMENT");
        l.setTorrentTitle(title);
        l.setAccepted(accepted);
        l.setReasonCode(code);
        // 实体上是字符串，与 MyMetaObjectHandler 的填充格式一致；末尾带 .0 模拟部分驱动读回来的样子
        l.setCreateTime(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(NOW - minutesAgo * 60_000L)) + ".0");
        logs.add(l);
    }

    @Test
    void 只统计最近一轮_按原因计数从多到少() {
        episode(5, "MISSING", TODAY.minusDays(1));
        // 上一轮（两天前）的淘汰不该混进来
        log(5, 2 * 24 * 60, "Old.S01E05", "0", "NOT_FREE");
        log(5, 3, "A.S01E05.720p", "0", "RESOLUTION_NOT_ALLOWED");
        log(5, 2, "B.S01E05.720p", "0", "RESOLUTION_NOT_ALLOWED");
        log(5, 1, "C.S01E05", "0", "NOT_FREE");

        SubscriptionDiagnosisService.EpisodeDiagnosis ep = service.diagnose(1, TODAY).episodes().get(0);

        assertEquals(3, ep.candidates());
        assertEquals("分辨率不在白名单", ep.reasons().get(0).label());
        assertEquals(2, ep.reasons().get(0).count());
        assertTrue(ep.summary().contains("3 个候选，2 个「分辨率不在白名单」、1 个「非免费种」"), ep.summary());
    }

    /** 季包日志（集号 -1）覆盖整季，每一集都要算上，否则「只搜到季包、全被淘汰」会显示成没搜过 */
    @Test
    void 季包日志算到每一集上() {
        episode(3, "MISSING", TODAY.minusDays(3));
        episode(4, "MISSING", TODAY.minusDays(2));
        log(SubscriptionMatcher.SEASON_PACK, 1, "Show.S01.2160p", "0", "SIZE_ABOVE_MAX");

        List<SubscriptionDiagnosisService.EpisodeDiagnosis> eps = service.diagnose(1, TODAY).episodes();

        assertEquals(1, eps.get(0).candidates());
        assertEquals(1, eps.get(1).candidates());
    }

    @Test
    void 未播出的集不诊断_播出日期未知的按已播出() {
        episode(1, "MISSING", null);
        episode(2, "MISSING", TODAY.plusDays(3));

        SubscriptionDiagnosisService.Diagnosis d = service.diagnose(1, TODAY);

        assertEquals(1, d.episodes().size());
        assertEquals(1, d.pendingTotal());
        assertEquals(1, d.episodes().get(0).episode());
    }

    /** 没开自动补搜是订阅级的前提问题：不先说出来，用户会逐集去调过滤规则 */
    @Test
    void 未开自动补搜_先给出订阅级提示_没搜过的集建议立即补搜() {
        sub.setAutoSearch("0");
        episode(7, "MISSING", TODAY.minusDays(1));

        SubscriptionDiagnosisService.Diagnosis d = service.diagnose(1, TODAY);

        assertTrue(d.notes().stream().anyMatch(n -> n.contains("未开启自动补搜")), d.notes().toString());
        assertTrue(d.episodes().get(0).summary().contains("立即补搜"), d.episodes().get(0).summary());
    }

    @Test
    void 没有启用的索引器_给出提示() {
        when(indexerService.listEnabled()).thenReturn(List.of());

        assertTrue(service.diagnose(1, TODAY).notes().stream().anyMatch(n -> n.contains("索引器")));
    }

    @Test
    void 在途的集不看搜索日志_直接指向下载记录() {
        episode(8, "IN_FLIGHT", TODAY.minusDays(1));
        log(8, 1, "X.S01E08", "0", "NOT_FREE");

        SubscriptionDiagnosisService.EpisodeDiagnosis ep = service.diagnose(1, TODAY).episodes().get(0);

        assertEquals(0, ep.candidates());
        assertTrue(ep.summary().contains("下载记录"), ep.summary());
        assertFalse(ep.summary().contains("非免费"));
    }
}
