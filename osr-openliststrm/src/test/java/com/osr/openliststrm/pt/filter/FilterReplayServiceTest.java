package com.osr.openliststrm.pt.filter;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回放比的是「已保存的规则」与「草稿」，旧日志只比标题维度——这两条判错了都不报错，
 * 只会给出一个看着合理、实则误导的数字。
 */
class FilterReplayServiceTest {

    private final IPtSearchLogPlusService logService = mock(IPtSearchLogPlusService.class);
    private final IPtSubscriptionPlusService subscriptionService = mock(IPtSubscriptionPlusService.class);
    private final IPtFilterConfigPlusService filterConfigService = mock(IPtFilterConfigPlusService.class);
    private final IPtTorrentBlacklistPlusService blacklistService = mock(IPtTorrentBlacklistPlusService.class);
    private final SubscriptionEngine subscriptionEngine = mock(SubscriptionEngine.class);
    private final FilterReplayService service = new FilterReplayService(logService, subscriptionService,
            filterConfigService, blacklistService, subscriptionEngine, new TorrentFilterEngine());

    private final List<PtSearchLogPlus> logs = new ArrayList<>();
    private PtFilterConfigPlus saved;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        saved = new PtFilterConfigPlus();
        saved.setMinSeeders(0);
        saved.setMinSize(0L);
        saved.setMaxSize(0L);
        saved.setFreeOnly("0");
        when(filterConfigService.getConfig()).thenReturn(saved);
        when(logService.list(any(Wrapper.class))).thenReturn(logs);
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(1);
        sub.setTitle("三体");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub));
        when(blacklistService.list()).thenReturn(List.of());
        // 只模拟分辨率解析：标题里带什么就是什么
        doAnswer(inv -> {
            TorrentInfo t = inv.getArgument(0);
            t.setParsedResolution(t.getTitle().contains("2160p") ? "2160p" : t.getTitle().contains("720p") ? "720p" : "1080p");
            return null;
        }).when(subscriptionEngine).fillParsed(any());
    }

    private PtSearchLogPlus log(long id, String title, Long size) {
        PtSearchLogPlus l = new PtSearchLogPlus();
        l.setId((int) id);
        l.setSubId(1);
        l.setIndexerId(1);
        l.setTorrentTitle(title);
        l.setTorrentSize(size);
        l.setSeeders(size == null ? null : 5);
        logs.add(l);
        return l;
    }

    private PtFilterConfigPlus draft() {
        PtFilterConfigPlus d = FilterReplayService.neutralize(saved);
        d.setMinSeeders(saved.getMinSeeders());
        return d;
    }

    @Test
    void 草稿加了分辨率白名单_列出会新被淘汰的候选() {
        log(1, "Show.S01E01.720p", 1_000_000_000L);
        log(2, "Show.S01E01.1080p", 2_000_000_000L);
        PtFilterConfigPlus draft = draft();
        draft.setResolutionWhitelist("1080p,2160p");

        FilterReplayService.ReplayResult r = service.replay(draft, 7);

        assertEquals(2, r.evaluated());
        assertEquals(1, r.newlyRejected().total());
        assertEquals("Show.S01E01.720p", r.newlyRejected().examples().get(0).torrentTitle());
        assertEquals(0, r.newlyAccepted().total());
    }

    /** 基线是「现在的规则」：草稿与已保存的一样时，结论必然全部不变 */
    @Test
    void 草稿与已保存的相同_没有任何变化() {
        saved.setResolutionWhitelist("1080p");
        log(1, "Show.S01E01.720p", 1_000_000_000L);
        PtFilterConfigPlus same = draft();
        same.setResolutionWhitelist("1080p");

        assertEquals(0, service.replay(same, 7).changed());
    }

    /**
     * 旧日志没有体积：不中和的话体积 0 会被「最小体积」一律挡掉，调体积下限看起来就像
     * 「把所有候选都变成了新淘汰」，完全是假象。
     */
    @Test
    void 旧日志没有体积_只比标题维度_调体积下限不产生变化() {
        log(1, "Show.S01E01.1080p", null);
        PtFilterConfigPlus draft = draft();
        draft.setMinSize(500_000_000L);

        FilterReplayService.ReplayResult r = service.replay(draft, 7);

        assertEquals(1, r.titleOnly());
        assertEquals(0, r.changed());
    }

    @Test
    void 有画像的日志_体积下限照常生效() {
        log(1, "Show.S01E01.1080p", 100_000_000L);
        PtFilterConfigPlus draft = draft();
        draft.setMinSize(500_000_000L);

        FilterReplayService.ReplayResult r = service.replay(draft, 7);

        assertEquals(1, r.newlyRejected().total());
        assertFalse(r.newlyRejected().examples().get(0).titleOnly());
    }

    @Test
    void 同一候选多次出现只算一次() {
        log(3, "Show.S01E01.720p", 1L);
        log(2, "Show.S01E01.720p", 1L);

        assertEquals(1, service.replay(draft(), 7).evaluated());
    }

    @Test
    void 订阅覆盖里的数值键也要中和() {
        String neutral = FilterReplayService.neutralizeOverride("{\"minSize\":5,\"freeOnly\":\"1\",\"excludeKeywords\":\"国语\"}");

        var json = JSON.parseObject(neutral);
        assertFalse(json.containsKey("minSize"));
        assertFalse(json.containsKey("freeOnly"));
        assertTrue(json.containsKey("excludeKeywords"), "标题维度的覆盖要保留");
    }
}
