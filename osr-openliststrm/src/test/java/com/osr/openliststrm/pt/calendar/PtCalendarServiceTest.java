package com.osr.openliststrm.pt.calendar;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.calendar.dto.CalendarEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtCalendarServiceTest {

    @Mock
    private IPtSubscriptionEpisodePlusService episodeService;

    @Mock
    private IPtSubscriptionPlusService subscriptionService;

    @InjectMocks
    private PtCalendarService service;

    /** 「全部可见」的归属判据，供不关心权限的既有用例使用 */
    private static final Predicate<PtSubscriptionPlus> ALL = sub -> true;

    private static Date d(String iso) {
        return Date.from(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static PtSubscriptionEpisodePlus ep(int id, int subId, int episode, String airDate, String state) {
        PtSubscriptionEpisodePlus e = new PtSubscriptionEpisodePlus();
        e.setId(id);
        e.setSubId(subId);
        e.setEpisode(episode);
        e.setState(state);
        e.setAirDate(airDate == null ? null : d(airDate));
        return e;
    }

    private static PtSubscriptionPlus sub(int id, String title) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setTitle(title);
        s.setTmdbId("100" + id);
        s.setSeason(1);
        s.setPosterPath("/p" + id + ".jpg");
        return s;
    }

    @SuppressWarnings("unchecked")
    private void givenEpisodes(List<PtSubscriptionEpisodePlus> episodes) {
        when(episodeService.list(any(Wrapper.class))).thenReturn(episodes);
    }

    @Test
    void 区间非法时拒绝查询() {
        assertThrows(IllegalArgumentException.class, () -> service.query(null, LocalDate.parse("2026-08-31"), ALL));
        assertThrows(IllegalArgumentException.class, () -> service.query(LocalDate.parse("2026-08-01"), null, ALL));
        assertThrows(IllegalArgumentException.class,
                () -> service.query(LocalDate.parse("2026-08-31"), LocalDate.parse("2026-08-01"), ALL));
        verify(episodeService, never()).list(any(Wrapper.class));
    }

    @Test
    void 跨度超过一年时拒绝_不把整张表拉进内存() {
        LocalDate start = LocalDate.parse("2020-01-01");
        assertThrows(IllegalArgumentException.class, () -> service.query(start, start.plusDays(400), ALL));
        // 边界上刚好一年是允许的
        givenEpisodes(List.of());
        assertTrue(service.query(start, start.plusDays(PtCalendarService.MAX_RANGE_DAYS), ALL).isEmpty());
    }

    @Test
    void 按日期_剧名_集号排序() {
        givenEpisodes(List.of(
                ep(3, 2, 5, "2026-08-12", "MISSING"),
                ep(1, 1, 2, "2026-08-12", "IN_LIBRARY"),
                ep(2, 1, 1, "2026-08-12", "IN_LIBRARY"),
                ep(4, 1, 9, "2026-08-10", "IN_FLIGHT")));
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub(1, "三体"), sub(2, "沙丘")));

        List<CalendarEntry> result = service.query(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), ALL);

        assertEquals(List.of("2026-08-10|三体|9", "2026-08-12|三体|1", "2026-08-12|三体|2", "2026-08-12|沙丘|5"),
                result.stream().map(c -> c.airDate() + "|" + c.title() + "|" + c.episode()).toList());
    }

    @Test
    void 订阅已删除但集行还在时跳过该集() {
        givenEpisodes(List.of(
                ep(1, 1, 1, "2026-08-12", "MISSING"),
                ep(2, 99, 1, "2026-08-12", "MISSING")));
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub(1, "三体")));

        List<CalendarEntry> result = service.query(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), ALL);

        assertEquals(1, result.size());
        assertEquals("三体", result.get(0).title());
    }

    @Test
    void 带出订阅的海报与季号供前端展示() {
        givenEpisodes(List.of(ep(1, 1, 6, "2026-08-12", "IN_FLIGHT")));
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub(1, "三体")));

        CalendarEntry entry = service.query(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), ALL).get(0);

        assertEquals("/p1.jpg", entry.posterPath());
        assertEquals(1, entry.season());
        assertEquals(6, entry.episode());
        assertEquals("IN_FLIGHT", entry.state());
        assertEquals("1001", entry.tmdbId());
    }

    @Test
    void 区间内无排播时返回空表且不查订阅() {
        givenEpisodes(List.of());

        assertTrue(service.query(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), ALL).isEmpty());
        verify(subscriptionService, never()).listByIds(any());
    }

    // ---------- 归属过滤与暂停过滤 ----------

    /**
     * 日历是 pt_subscription 的第三个消费者，此前唯独它没有归属判定——
     * 非管理员会在格子里看到全站所有人订阅的剧名、海报与季集号。
     */
    @Test
    void 只返回归属判据放行的订阅() {
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                ep(1, 10, 1, "2026-08-05", "MISSING"),
                ep(2, 20, 1, "2026-08-06", "MISSING")));
        PtSubscriptionPlus mine = sub(10, "我的剧");
        PtSubscriptionPlus other = sub(20, "别人的剧");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(mine, other));

        List<CalendarEntry> result = service.query(LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-31"), s -> s.getId() == 10);

        assertEquals(1, result.size());
        assertEquals("我的剧", result.get(0).title());
    }

    /** 判据全部拒绝时返回空列表，而不是漏出任何一条 */
    @Test
    void 归属判据全部拒绝时返回空() {
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                ep(1, 10, 1, "2026-08-05", "MISSING")));
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub(10, "剧")));

        assertTrue(service.query(LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-31"), s -> false).isEmpty());
    }

    /** 暂停的订阅不进日历：用户已经明确表态不再追它，占着格子只是噪音 */
    @Test
    void 暂停的订阅不进日历() {
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                ep(1, 10, 1, "2026-08-05", "MISSING"),
                ep(2, 20, 1, "2026-08-06", "MISSING")));
        PtSubscriptionPlus active = sub(10, "在追");
        active.setStatus("ACTIVE");
        PtSubscriptionPlus paused = sub(20, "暂停了");
        paused.setStatus("PAUSED");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(active, paused));

        List<CalendarEntry> result = service.query(LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-31"), ALL);

        assertEquals(1, result.size());
        assertEquals("在追", result.get(0).title());
    }

    /** 已完成的订阅照常展示：日历陈述的是「哪天播什么」，追完了不代表那天没播 */
    @Test
    void 已完成的订阅照常进日历() {
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                ep(1, 10, 1, "2026-08-05", "IN_LIBRARY")));
        PtSubscriptionPlus done = sub(10, "追完了");
        done.setStatus("COMPLETED");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(done));

        assertEquals(1, service.query(LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-31"), ALL).size());
    }

    /** status 为 NULL 的历史行不能被当成暂停滤掉 */
    @Test
    void 状态为空的订阅按未暂停处理() {
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                ep(1, 10, 1, "2026-08-05", "MISSING")));
        PtSubscriptionPlus legacy = sub(10, "历史订阅");
        legacy.setStatus(null);
        when(subscriptionService.listByIds(any())).thenReturn(List.of(legacy));

        assertEquals(1, service.query(LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-31"), ALL).size());
    }
}
