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
        assertThrows(IllegalArgumentException.class, () -> service.query(null, LocalDate.parse("2026-08-31")));
        assertThrows(IllegalArgumentException.class, () -> service.query(LocalDate.parse("2026-08-01"), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.query(LocalDate.parse("2026-08-31"), LocalDate.parse("2026-08-01")));
        verify(episodeService, never()).list(any(Wrapper.class));
    }

    @Test
    void 跨度超过一年时拒绝_不把整张表拉进内存() {
        LocalDate start = LocalDate.parse("2020-01-01");
        assertThrows(IllegalArgumentException.class, () -> service.query(start, start.plusDays(400)));
        // 边界上刚好一年是允许的
        givenEpisodes(List.of());
        assertTrue(service.query(start, start.plusDays(PtCalendarService.MAX_RANGE_DAYS)).isEmpty());
    }

    @Test
    void 按日期_剧名_集号排序() {
        givenEpisodes(List.of(
                ep(3, 2, 5, "2026-08-12", "MISSING"),
                ep(1, 1, 2, "2026-08-12", "IN_LIBRARY"),
                ep(2, 1, 1, "2026-08-12", "IN_LIBRARY"),
                ep(4, 1, 9, "2026-08-10", "IN_FLIGHT")));
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub(1, "三体"), sub(2, "沙丘")));

        List<CalendarEntry> result = service.query(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));

        assertEquals(List.of("2026-08-10|三体|9", "2026-08-12|三体|1", "2026-08-12|三体|2", "2026-08-12|沙丘|5"),
                result.stream().map(c -> c.airDate() + "|" + c.title() + "|" + c.episode()).toList());
    }

    @Test
    void 订阅已删除但集行还在时跳过该集() {
        givenEpisodes(List.of(
                ep(1, 1, 1, "2026-08-12", "MISSING"),
                ep(2, 99, 1, "2026-08-12", "MISSING")));
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub(1, "三体")));

        List<CalendarEntry> result = service.query(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));

        assertEquals(1, result.size());
        assertEquals("三体", result.get(0).title());
    }

    @Test
    void 带出订阅的海报与季号供前端展示() {
        givenEpisodes(List.of(ep(1, 1, 6, "2026-08-12", "IN_FLIGHT")));
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub(1, "三体")));

        CalendarEntry entry = service.query(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31")).get(0);

        assertEquals("/p1.jpg", entry.posterPath());
        assertEquals(1, entry.season());
        assertEquals(6, entry.episode());
        assertEquals("IN_FLIGHT", entry.state());
        assertEquals("1001", entry.tmdbId());
    }

    @Test
    void 区间内无排播时返回空表且不查订阅() {
        givenEpisodes(List.of());

        assertTrue(service.query(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31")).isEmpty());
        verify(subscriptionService, never()).listByIds(any());
    }
}
