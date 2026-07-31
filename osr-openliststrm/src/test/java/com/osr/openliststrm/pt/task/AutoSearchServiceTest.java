package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoSearchServiceTest {

    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtFilterConfigPlusService filterConfigService;
    @Mock private SearchSupplementService searchSupplementService;

    private AutoSearchService service() {
        return new AutoSearchService(subscriptionService, filterConfigService, searchSupplementService);
    }

    private PtSubscriptionPlus sub(int id, String mediaType, int season, String autoSearch, Date lastSearchTime) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setMediaType(mediaType);
        s.setTitle(mediaType.equals("MOVIE") ? "Some Movie" : "Some Show");
        s.setSeason(season);
        s.setTotalEpisodes(10);
        s.setStatus("ACTIVE");
        s.setAutoSearch(autoSearch);
        s.setLastSearchTime(lastSearchTime);
        s.setLastAutoSearchNoResult("0");
        return s;
    }

    private PtFilterConfigPlus config(Integer intervalHours) {
        PtFilterConfigPlus c = new PtFilterConfigPlus();
        c.setAutoSearchIntervalHours(intervalHours);
        return c;
    }

    @Test
    void 未开启自动补搜的订阅_跳过() {
        when(subscriptionService.listActive()).thenReturn(List.of(sub(10, "TV", 1, "0", null)));
        when(filterConfigService.getConfig()).thenReturn(config(24));

        service().run();

        verify(searchSupplementService, never()).searchAndPushMissing(10);
    }

    @Test
    void 从未搜索过_视为到期_发起搜索() {
        when(subscriptionService.listActive()).thenReturn(List.of(sub(10, "TV", 1, "1", null)));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(10))
                .thenReturn(new SearchAndPushSummary(false, true, 0));

        service().run();

        verify(searchSupplementService).searchAndPushMissing(10);
    }

    @Test
    void 未到周期_跳过() {
        Date recent = new Date(System.currentTimeMillis() - 3600_000L);
        when(subscriptionService.listActive()).thenReturn(List.of(sub(10, "TV", 1, "1", recent)));
        when(filterConfigService.getConfig()).thenReturn(config(24));

        service().run();

        verify(searchSupplementService, never()).searchAndPushMissing(10);
    }

    @Test
    void 已过周期_发起搜索() {
        Date old = new Date(System.currentTimeMillis() - 25L * 3600_000L);
        when(subscriptionService.listActive()).thenReturn(List.of(sub(10, "TV", 1, "1", old)));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(10))
                .thenReturn(new SearchAndPushSummary(false, true, 0));

        service().run();

        verify(searchSupplementService).searchAndPushMissing(10);
    }

    @Test
    void 无缺集或订阅不可搜_跳过不更新通知标记() {
        when(subscriptionService.listActive()).thenReturn(List.of(sub(10, "TV", 1, "1", null)));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(10)).thenReturn(SearchAndPushSummary.skip());

        service().run();

        verify(subscriptionService, never()).updateById(any(PtSubscriptionPlus.class));
    }

    @Test
    void 电影订阅_按id发起搜索() {
        when(subscriptionService.listActive()).thenReturn(List.of(sub(20, "MOVIE", 0, "1", null)));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(20))
                .thenReturn(new SearchAndPushSummary(false, true, 0));

        service().run();

        verify(searchSupplementService).searchAndPushMissing(20);
    }

    @Test
    void 全局周期未配置_默认24小时() {
        Date old = new Date(System.currentTimeMillis() - 25L * 3600_000L);
        when(subscriptionService.listActive()).thenReturn(List.of(sub(10, "TV", 1, "1", old)));
        when(filterConfigService.getConfig()).thenReturn(config(null));
        when(searchSupplementService.searchAndPushMissing(10))
                .thenReturn(new SearchAndPushSummary(false, true, 0));

        service().run();

        verify(searchSupplementService).searchAndPushMissing(10);
    }

    @Test
    void 单个订阅搜索抛异常_不影响其他订阅() {
        when(subscriptionService.listActive()).thenReturn(List.of(
                sub(10, "TV", 1, "1", null), sub(11, "TV", 1, "1", null)));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(10)).thenThrow(new RuntimeException("boom"));
        when(searchSupplementService.searchAndPushMissing(11))
                .thenReturn(new SearchAndPushSummary(false, true, 0));

        service().run();

        verify(searchSupplementService).searchAndPushMissing(11);
    }

    // ---------- 散集补齐 ----------

    @Test
    void 季包未命中但补到散集_视为命中不通知() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(sub(10, "TV", 1, "1", null)));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(10))
                .thenReturn(new SearchAndPushSummary(false, false, 3));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    // ---------- 落空通知去重 ----------

    @Test
    void 首次落空_发一次通知并记录标记() throws Exception {
        PtSubscriptionPlus s = sub(10, "TV", 1, "1", null);
        when(subscriptionService.listActive()).thenReturn(List.of(s));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(10))
                .thenReturn(new SearchAndPushSummary(false, false, 0));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("未找到可用资源"))));
            ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
            verify(subscriptionService).updateById(captor.capture());
            assertEquals("1", captor.getValue().getLastAutoSearchNoResult());
        }
    }

    @Test
    void 已经落空过_连续落空不重复通知() throws Exception {
        PtSubscriptionPlus s = sub(10, "TV", 1, "1", null);
        s.setLastAutoSearchNoResult("1");
        when(subscriptionService.listActive()).thenReturn(List.of(s));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(10))
                .thenReturn(new SearchAndPushSummary(false, false, 0));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
            verify(subscriptionService, never()).updateById(any(PtSubscriptionPlus.class));
        }
    }

    @Test
    void 落空后再次命中_重置标记不发通知() throws Exception {
        PtSubscriptionPlus s = sub(10, "TV", 1, "1", null);
        s.setLastAutoSearchNoResult("1");
        when(subscriptionService.listActive()).thenReturn(List.of(s));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(10))
                .thenReturn(new SearchAndPushSummary(false, true, 0));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
            ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
            verify(subscriptionService).updateById(captor.capture());
            assertEquals("0", captor.getValue().getLastAutoSearchNoResult());
        }
    }

}
