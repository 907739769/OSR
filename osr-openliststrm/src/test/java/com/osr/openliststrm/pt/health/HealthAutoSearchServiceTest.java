package com.osr.openliststrm.pt.health;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HealthAutoSearchServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Mock
    private EpisodeHealthService healthService;
    @Mock
    private IPtSubscriptionPlusService subscriptionService;
    @Mock
    private IPtSubscriptionEpisodePlusService episodeService;
    @Mock
    private OpenlistConfig openlistConfig;

    private HealthAutoSearchService service() {
        return new HealthAutoSearchService(healthService, subscriptionService, episodeService, openlistConfig);
    }

    private static PtSubscriptionPlus sub(int id, String autoSearch) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setTitle("剧" + id);
        s.setStatus("ACTIVE");
        s.setAutoSearch(autoSearch);
        s.setHealthIgnored("0");
        return s;
    }

    private static SubscriptionHealth overdue(PtSubscriptionPlus s) {
        return new SubscriptionHealth(s, List.of(new EpisodeHealthItem(5, "MISSING", "2026-09-20", 10,
                EpisodeHealthBucket.OVERDUE_MISSING.name(), EpisodeHealthDiagnosis.AUTO_SEARCH_OFF.name())));
    }

    private static PtSubscriptionEpisodePlus ep(int episode, String state, LocalDate airDate) {
        PtSubscriptionEpisodePlus e = new PtSubscriptionEpisodePlus();
        e.setEpisode(episode);
        e.setState(state);
        e.setAirDate(airDate == null ? null : date(airDate));
        return e;
    }

    private static Date date(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void 只给有逾期缺失_未开补搜_未忽略的订阅开启() {
        PtSubscriptionPlus off = sub(1, "0");
        PtSubscriptionPlus alreadyOn = sub(2, "1");
        PtSubscriptionPlus ignored = sub(3, "0");
        ignored.setHealthIgnored("1");
        // 只有在途逾期、没有逾期缺失：补搜对它无事可做
        PtSubscriptionPlus inFlightOnly = sub(4, "0");
        SubscriptionHealth inFlight = new SubscriptionHealth(inFlightOnly, List.of(new EpisodeHealthItem(1, "IN_FLIGHT",
                "2026-09-20", 10, EpisodeHealthBucket.OVERDUE_IN_FLIGHT.name(), EpisodeHealthDiagnosis.DOWNLOADING.name())));
        when(subscriptionService.enableAutoSearchByHealth(any(), any())).thenReturn(1);

        List<PtSubscriptionPlus> enabled = service().enableOverdue(
                List.of(overdue(off), overdue(alreadyOn), overdue(ignored), inFlight));

        assertEquals(List.of(off), enabled);
        verify(subscriptionService).enableAutoSearchByHealth(eq(List.of(1)), any());
    }

    @Test
    void 开关关着时不开启_但照样关掉自己此前打开的() {
        when(openlistConfig.isHealthAutoSearchEnabled()).thenReturn(false);
        PtSubscriptionPlus completed = sub(1, "1");
        completed.setStatus("COMPLETED");
        completed.setHealthAutoSearchTime(date(TODAY.minusDays(1)));
        when(subscriptionService.listHealthAutoSearched()).thenReturn(List.of(completed));
        when(episodeService.listBySubscription(1)).thenReturn(List.of());
        when(subscriptionService.disableHealthAutoSearch(1)).thenReturn(true);

        HealthAutoSearchService.Outcome outcome = service().apply();

        assertTrue(outcome.enabled().isEmpty());
        assertEquals(List.of(completed), outcome.disabled());
        verify(healthService, never()).scan(any());
        verify(subscriptionService, never()).enableAutoSearchByHealth(any(), any());
    }

    @Test
    void 还有已播缺集或在途集时不关() {
        PtSubscriptionPlus s = sub(1, "1");
        s.setHealthAutoSearchTime(date(TODAY.minusDays(30)));
        assertFalse(HealthAutoSearchService.shouldDisable(s,
                List.of(ep(1, "MISSING", TODAY.minusDays(3))), TODAY));
        assertFalse(HealthAutoSearchService.shouldDisable(s,
                List.of(ep(1, "IN_FLIGHT", TODAY.minusDays(3))), TODAY));
    }

    @Test
    void 只剩未播集时按空闲天数判_最近命中在七天内不关() {
        // 追更中的剧：刚补上一集、下一集还没播。只看「当前不缺」就关的话，
        // 每周新播一集都要开关一轮
        PtSubscriptionPlus s = sub(1, "1");
        s.setHealthAutoSearchTime(date(TODAY.minusDays(30)));
        s.setLastMatchTime(date(TODAY.minusDays(2)));
        List<PtSubscriptionEpisodePlus> eps = List.of(
                ep(1, "IN_LIBRARY", TODAY.minusDays(9)),
                ep(2, "MISSING", TODAY.plusDays(5)));
        assertFalse(HealthAutoSearchService.shouldDisable(s, eps, TODAY));

        s.setLastMatchTime(date(TODAY.minusDays(HealthAutoSearchService.IDLE_DAYS)));
        assertTrue(HealthAutoSearchService.shouldDisable(s, eps, TODAY));
    }

    @Test
    void 刚打开还没满七天_即使没缺集也不关() {
        PtSubscriptionPlus s = sub(1, "1");
        s.setHealthAutoSearchTime(date(TODAY.minusDays(1)));
        assertFalse(HealthAutoSearchService.shouldDisable(s,
                List.of(ep(1, "IN_LIBRARY", TODAY.minusDays(9))), TODAY));
    }

    @Test
    void 关闭走条件更新_期间被用户接管的不算关掉() {
        PtSubscriptionPlus s = sub(1, "1");
        s.setStatus("PAUSED");
        s.setHealthAutoSearchTime(date(TODAY.minusDays(1)));
        when(subscriptionService.listHealthAutoSearched()).thenReturn(List.of(s));
        when(episodeService.listBySubscription(anyInt())).thenReturn(List.of());
        when(subscriptionService.disableHealthAutoSearch(1)).thenReturn(false);

        assertTrue(service().disableIdle(TODAY).isEmpty());
    }
}
