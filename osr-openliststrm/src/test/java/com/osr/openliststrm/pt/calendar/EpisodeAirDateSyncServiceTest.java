package com.osr.openliststrm.pt.calendar;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 手动对账入口的 {@link EpisodeAirDateSyncService#syncQuietly}：它挂在对账按钮前面，
 * 任何失败都不能把对账本身带崩。
 */
class EpisodeAirDateSyncServiceTest {

    private IPtSubscriptionPlusService subscriptionService;
    private IPtSubscriptionEpisodePlusService episodeService;
    private TmdbSearchService tmdbSearchService;
    private EpisodeAirDateSyncService service;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(IPtSubscriptionPlusService.class);
        episodeService = mock(IPtSubscriptionEpisodePlusService.class);
        tmdbSearchService = mock(TmdbSearchService.class);
        service = new EpisodeAirDateSyncService(subscriptionService, episodeService, tmdbSearchService);
    }

    private static PtSubscriptionPlus sub(String mediaType) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(1);
        sub.setTitle("测试剧");
        sub.setTmdbId("100");
        sub.setSeason(1);
        sub.setMediaType(mediaType);
        return sub;
    }

    @Test
    void 电影订阅_不打TMDb() {
        when(subscriptionService.getById(1)).thenReturn(sub(SubscriptionService.TYPE_MOVIE));

        assertEquals(0, service.syncQuietly(1));
        verifyNoInteractions(tmdbSearchService);
    }

    @Test
    void 订阅不存在_返回0() {
        assertEquals(0, service.syncQuietly(1));
        verifyNoInteractions(tmdbSearchService);
    }

    /** TMDb 挂了只记日志：对账照常进行，日期留给 12 小时一轮的定时同步 */
    @Test
    void TMDb抛异常_吞掉返回0() {
        when(subscriptionService.getById(1)).thenReturn(sub("TV"));
        when(tmdbSearchService.getSeasonEpisodeAirDates(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("TMDb 超时"));

        assertEquals(0, service.syncQuietly(1));
        verify(episodeService, never()).updateBatchById(any());
    }
}
