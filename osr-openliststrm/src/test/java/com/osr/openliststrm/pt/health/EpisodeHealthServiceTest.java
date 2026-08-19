package com.osr.openliststrm.pt.health;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthItem;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthReport;
import com.osr.openliststrm.pt.health.dto.SubscriptionHealthItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EpisodeHealthServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 17);

    @Mock
    private IPtSubscriptionEpisodePlusService episodeService;

    @Mock
    private IPtSubscriptionPlusService subscriptionService;

    private EpisodeHealthService service(int overdueDays) {
        return new EpisodeHealthService(episodeService, subscriptionService, overdueDays);
    }

    private static Date d(String iso) {
        return Date.from(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static PtSubscriptionEpisodePlus ep(int subId, int episode, String airDate, String state) {
        PtSubscriptionEpisodePlus e = new PtSubscriptionEpisodePlus();
        e.setId(subId * 1000 + episode);
        e.setSubId(subId);
        e.setEpisode(episode);
        e.setState(state);
        e.setAirDate(airDate == null ? null : d(airDate));
        return e;
    }

    private static PtSubscriptionPlus sub(int id, String title, String autoSearch) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setTitle(title);
        s.setTmdbId("100" + id);
        s.setMediaType("TV");
        s.setSeason(1);
        s.setStatus("ACTIVE");
        s.setAutoSearch(autoSearch);
        return s;
    }

    private void given(List<PtSubscriptionEpisodePlus> episodes, PtSubscriptionPlus... subs) {
        when(episodeService.listHealthCandidates(any(Date.class))).thenReturn(episodes);
        when(subscriptionService.listByIds(any())).thenReturn(List.of(subs));
    }

    private EpisodeHealthItem only(List<SubscriptionHealth> scanned) {
        assertEquals(1, scanned.size());
        assertEquals(1, scanned.get(0).episodes().size());
        return scanned.get(0).episodes().get(0);
    }

    @Test
    void 查询上界是今天减去阈值天数_未播够时间的集在SQL层就被排除() {
        given(List.of());

        service(3).scan(TODAY);

        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(episodeService).listHealthCandidates(captor.capture());
        assertEquals(d("2026-08-14"), captor.getValue());
    }

    @Test
    void 逾期天数按播出日期算_MISSING归入逾期缺失档() {
        given(List.of(ep(1, 5, "2026-08-10", "MISSING")), sub(1, "三体", "1"));

        EpisodeHealthItem item = only(service(3).scan(TODAY));

        assertEquals(EpisodeHealthBucket.OVERDUE_MISSING.name(), item.bucket());
        assertEquals(7, item.overdueDays());
        assertEquals("2026-08-10", item.airDate());
    }

    @Test
    void 没有播出日期时逾期天数为null而不是0_并单独分档() {
        // 0 的语义是「今天刚播」，与「算不出来」是两回事。混用会让前端按天数倒序时
        // 把一批未定档的集顶到最前面，把真正逾期的挤下去
        given(List.of(ep(1, 5, null, "MISSING")), sub(1, "尚未定档的剧", "1"));

        EpisodeHealthItem item = only(service(3).scan(TODAY));

        assertEquals(EpisodeHealthBucket.NO_AIR_DATE.name(), item.bucket());
        assertNull(item.overdueDays());
    }

    @Test
    void 熔断优先于无播出日期_自动链路已放弃与日期缺失是两回事() {
        given(List.of(ep(1, 5, null, "BLOCKED")), sub(1, "三体", "1"));

        EpisodeHealthItem item = only(service(3).scan(TODAY));

        assertEquals(EpisodeHealthBucket.BLOCKED.name(), item.bucket());
        assertEquals(EpisodeHealthDiagnosis.BLOCKED.name(), item.diagnosis());
    }

    @Test
    void 没开自动补搜的缺集诊断成未开启补搜() {
        given(List.of(ep(1, 5, "2026-08-10", "MISSING")), sub(1, "三体", "0"));

        assertEquals(EpisodeHealthDiagnosis.AUTO_SEARCH_OFF.name(), only(service(3).scan(TODAY)).diagnosis());
    }

    @Test
    void 补搜开着但还没落空过时诊断成等待下一轮() {
        PtSubscriptionPlus s = sub(1, "三体", "1");
        s.setLastAutoSearchNoResult(0);
        given(List.of(ep(1, 5, "2026-08-10", "MISSING")), s);

        assertEquals(EpisodeHealthDiagnosis.SEARCHING.name(), only(service(3).scan(TODAY)).diagnosis());
    }

    @Test
    void 落空指纹为NO_CANDIDATE与为具体淘汰码时诊断相反() {
        PtSubscriptionPlus noCandidate = sub(1, "三体", "1");
        noCandidate.setLastAutoSearchNoResult(2);
        noCandidate.setLastAutoSearchRejectSign("NO_CANDIDATE");
        given(List.of(ep(1, 5, "2026-08-10", "MISSING")), noCandidate);
        assertEquals(EpisodeHealthDiagnosis.SEARCH_NO_CANDIDATE.name(), only(service(3).scan(TODAY)).diagnosis());

        PtSubscriptionPlus rejected = sub(2, "三体", "1");
        rejected.setLastAutoSearchNoResult(2);
        rejected.setLastAutoSearchRejectSign("NOT_FREE,SIZE_ABOVE_MAX");
        given(List.of(ep(2, 5, "2026-08-10", "MISSING")), rejected);
        assertEquals(EpisodeHealthDiagnosis.SEARCH_ALL_REJECTED.name(), only(service(3).scan(TODAY)).diagnosis());
    }

    @Test
    void 在途集按文件确认位分成两种诊断_已下好的不该被建议重下() {
        PtSubscriptionEpisodePlus confirmed = ep(1, 5, "2026-08-10", "IN_FLIGHT");
        confirmed.setFileConfirmed("1");
        PtSubscriptionEpisodePlus notConfirmed = ep(1, 6, "2026-08-10", "IN_FLIGHT");
        given(List.of(confirmed, notConfirmed), sub(1, "三体", "1"));

        List<EpisodeHealthItem> items = service(3).scan(TODAY).get(0).episodes();

        assertEquals(EpisodeHealthDiagnosis.UPLOAD_PENDING.name(), items.get(0).diagnosis());
        assertEquals(EpisodeHealthDiagnosis.DOWNLOADING.name(), items.get(1).diagnosis());
        assertTrue(items.stream().allMatch(i -> EpisodeHealthBucket.OVERDUE_IN_FLIGHT.name().equals(i.bucket())));
    }

    @Test
    void 电影订阅整体不参与体检_连同它唯一那行哨兵集记录一起被丢掉() {
        // 电影没有播出日期（日期同步按 media_type != MOVIE 取订阅），逾期天数恒为 null，
        // 于是每部没下到的电影都会常驻「无播出日期」档。而那样报出来是错的：电影上映后
        // 短期内本来就不会有资源，等几周是正常状态不是故障，只会把真正缺集的剧集淹掉
        PtSubscriptionPlus movie = sub(1, "某部电影", "1");
        movie.setMediaType("MOVIE");
        movie.setSeason(0);
        PtSubscriptionPlus tv = sub(2, "某部剧", "1");
        given(List.of(ep(1, 0, null, "MISSING"), ep(2, 3, "2026-08-10", "MISSING")), movie, tv);

        List<SubscriptionHealth> scanned = service(3).scan(TODAY);

        assertEquals(1, scanned.size());
        assertEquals("某部剧", scanned.get(0).subscription().getTitle());
    }

    @Test
    void 全是电影时报告为空_不残留空的订阅条目() {
        PtSubscriptionPlus movie = sub(1, "某部电影", "0");
        movie.setMediaType("MOVIE");
        given(List.of(ep(1, 0, null, "MISSING")), movie);

        EpisodeHealthReport report = service(3).report(s -> true);

        assertEquals(0, report.subscriptionCount());
        assertEquals(0, report.episodeCount());
        assertTrue(report.subscriptions().isEmpty());
    }

    @Test
    void 报告按逾期天数倒序_无播出日期的排在最后() {
        given(List.of(
                        ep(1, 1, "2026-08-14", "MISSING"),
                        ep(2, 1, null, "MISSING"),
                        ep(3, 1, "2026-08-01", "MISSING")),
                sub(1, "近的", "1"), sub(2, "无日期", "1"), sub(3, "远的", "1"));

        List<SubscriptionHealthItem> subs = service(3).report(s -> true).subscriptions();

        assertEquals(List.of("远的", "近的", "无日期"),
                subs.stream().map(SubscriptionHealthItem::title).toList());
        assertNull(subs.get(2).maxOverdueDays());
    }

    @Test
    void 归属过滤在报告层生效_看不到的订阅不进计数() {
        PtSubscriptionPlus mine = sub(1, "我的", "1");
        mine.setOwnerUserId(7L);
        PtSubscriptionPlus other = sub(2, "别人的", "1");
        other.setOwnerUserId(9L);
        given(List.of(ep(1, 1, "2026-08-10", "MISSING"), ep(2, 1, "2026-08-10", "MISSING")), mine, other);

        EpisodeHealthReport report = service(3).report(s -> Long.valueOf(7L).equals(s.getOwnerUserId()));

        assertEquals(1, report.subscriptionCount());
        assertEquals(1, report.episodeCount());
        assertEquals("我的", report.subscriptions().get(0).title());
    }

    @Test
    void 淘汰原因指纹被翻成中文标签_NO_CANDIDATE不翻() {
        PtSubscriptionPlus rejected = sub(1, "三体", "1");
        rejected.setLastAutoSearchNoResult(1);
        rejected.setLastAutoSearchRejectSign("NOT_FREE,LOW_SEEDERS");
        given(List.of(ep(1, 5, "2026-08-10", "MISSING")), rejected);

        assertEquals("非免费种、做种数不足", service(3).report(s -> true).subscriptions().get(0).rejectDetail());

        rejected.setLastAutoSearchRejectSign("NO_CANDIDATE");
        assertNull(service(3).report(s -> true).subscriptions().get(0).rejectDetail());
    }

    @Test
    void 计数按分档与诊断分别聚合_为空的档不出现在结果里() {
        given(List.of(
                        ep(1, 1, "2026-08-10", "MISSING"),
                        ep(1, 2, "2026-08-10", "MISSING"),
                        ep(1, 3, null, "MISSING")),
                sub(1, "三体", "0"));

        EpisodeHealthReport report = service(3).report(s -> true);

        assertEquals(2, report.bucketCounts().get(EpisodeHealthBucket.OVERDUE_MISSING.name()));
        assertEquals(1, report.bucketCounts().get(EpisodeHealthBucket.NO_AIR_DATE.name()));
        assertNull(report.bucketCounts().get(EpisodeHealthBucket.BLOCKED.name()));
        assertEquals(3, report.diagnosisCounts().get(EpisodeHealthDiagnosis.AUTO_SEARCH_OFF.name()));
    }

    // ---------- 忽略 ----------

    private static PtSubscriptionPlus ignored(int id, String title) {
        PtSubscriptionPlus s = sub(id, title, "0");
        s.setHealthIgnored("1");
        return s;
    }

    /**
     * 忽略是用户对「这部剧补不上」的表态，默认不该再出现在报告里——
     * 没有这个出口的话，一部片源根本不存在的老剧会永远躺在列表里、按周反复提醒，
     * 用户很快会把整类通知整个关掉。
     */
    @Test
    void 已忽略的订阅默认不进报告() {
        given(List.of(ep(1, 1, "2026-08-01", "MISSING"), ep(2, 1, "2026-08-01", "MISSING")),
                sub(1, "在看", "0"), ignored(2, "不想再报"));

        EpisodeHealthReport report = service(3).report(s -> true);

        assertEquals(List.of("在看"),
                report.subscriptions().stream().map(SubscriptionHealthItem::title).toList());
        assertEquals(1, report.subscriptionCount());
    }

    /**
     * ignoredCount 恒按全量算：它是前端渲染「显示已忽略(N)」那个入口的唯一依据，
     * 按当前视图算的话没包含时恒为 0、入口永不出现，忽略就成了无法撤销的操作。
     */
    @Test
    void 忽略计数恒按全量算与是否包含无关() {
        given(List.of(ep(1, 1, "2026-08-01", "MISSING"), ep(2, 1, "2026-08-01", "MISSING")),
                sub(1, "在看", "0"), ignored(2, "不想再报"));

        assertEquals(1, service(3).report(s -> true, false).ignoredCount());
        assertEquals(1, service(3).report(s -> true, true).ignoredCount());
    }

    /** 全部被忽略时，报告为空但仍要带出 ignoredCount，否则页面上没有任何入口能找回它们 */
    @Test
    void 全部被忽略时仍回传忽略计数() {
        given(List.of(ep(2, 1, "2026-08-01", "MISSING")), ignored(2, "不想再报"));

        EpisodeHealthReport report = service(3).report(s -> true);

        assertTrue(report.subscriptions().isEmpty());
        assertEquals(0, report.subscriptionCount());
        assertEquals(1, report.ignoredCount());
    }

    /** 显式要求包含时才回传，并带上 ignored 标记供前端渲染 */
    @Test
    void 显式包含时回传并带忽略标记() {
        given(List.of(ep(1, 1, "2026-08-01", "MISSING"), ep(2, 1, "2026-08-01", "MISSING")),
                sub(1, "在看", "0"), ignored(2, "不想再报"));

        List<SubscriptionHealthItem> subs = service(3).report(s -> true, true).subscriptions();

        assertEquals(2, subs.size());
        assertEquals(1, subs.stream().filter(SubscriptionHealthItem::ignored).count());
        assertEquals("不想再报", subs.stream().filter(SubscriptionHealthItem::ignored)
                .findFirst().orElseThrow().title());
    }

    /**
     * health_ignored 是后加的列，存量行为 NULL。判定必须写成「等于 '1' 才算忽略」——
     * 反过来写会让升级后所有历史订阅集体从体检里消失。
     */
    @Test
    void 忽略列为空的历史订阅按未忽略处理() {
        PtSubscriptionPlus legacy = sub(1, "历史订阅", "0");
        legacy.setHealthIgnored(null);
        given(List.of(ep(1, 1, "2026-08-01", "MISSING")), legacy);

        EpisodeHealthReport report = service(3).report(s -> true);

        assertEquals(1, report.subscriptionCount());
        assertEquals(0, report.ignoredCount());
        assertFalse(report.subscriptions().get(0).ignored());
    }

    /** 忽略只挡体检，不改变订阅本身是否还在被抓取——scan 照常把它算进来 */
    @Test
    void 忽略不影响扫描本身() {
        given(List.of(ep(2, 1, "2026-08-01", "MISSING")), ignored(2, "不想再报"));

        assertEquals(1, service(3).scan(TODAY).size());
    }
}
