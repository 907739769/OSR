package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoSearchServiceTest {

    /** id % 21 == 0 的订阅抖动为 0，用它测与抖动无关的行为，免得每条用例都要算偏移 */
    private static final int NO_JITTER_ID = 21;

    private static final long HOUR = 3600_000L;

    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtFilterConfigPlusService filterConfigService;
    @Mock private SearchSupplementService searchSupplementService;
    @Mock private IPtIndexerPlusService indexerService;

    private AutoSearchService service() {
        // 默认有启用中的索引器：一个都没有时整轮会被跳过（见 AutoSearchService#run）；
        // 单轮预算给 0 表示不限制，绝大多数用例不关心它
        return service(0);
    }

    private AutoSearchService service(long roundBudgetMillis) {
        when(indexerService.listEnabled()).thenReturn(List.of(new PtIndexerPlus()));
        return new AutoSearchService(subscriptionService, filterConfigService, searchSupplementService,
                indexerService, roundBudgetMillis);
    }

    private PtSubscriptionPlus sub(int id, String mediaType, int season, Date lastSearchTime) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setMediaType(mediaType);
        s.setTitle(mediaType.equals("MOVIE") ? "Some Movie" : "Some Show");
        s.setSeason(season);
        s.setTotalEpisodes(10);
        s.setStatus("ACTIVE");
        s.setAutoSearch("1");
        s.setLastSearchTime(lastSearchTime);
        s.setLastAutoSearchNoResult(0);
        return s;
    }

    private PtFilterConfigPlus config(Integer intervalHours) {
        PtFilterConfigPlus c = new PtFilterConfigPlus();
        c.setAutoSearchIntervalHours(intervalHours);
        return c;
    }

    private Date hoursAgo(long hours) {
        return new Date(System.currentTimeMillis() - hours * HOUR);
    }

    private void candidates(PtSubscriptionPlus... subs) {
        when(subscriptionService.listAutoSearchCandidates()).thenReturn(List.of(subs));
    }

    private void pushed(int subId) {
        when(searchSupplementService.searchAndPushMissing(subId))
                .thenReturn(new SearchAndPushSummary(false, true, 0));
    }

    /** @param signature 落空原因指纹；null 表示压根没搜到候选 */
    private void missed(int subId, String signature) {
        when(searchSupplementService.searchAndPushMissing(subId)).thenReturn(
                new SearchAndPushSummary(false, false, 0,
                        signature == null ? null : "3 个候选被过滤规则淘汰：3 个「非免费种」", signature));
    }

    // ---------- 候选选取 ----------

    @Test
    void 候选由sql收窄_不再拉全部ACTIVE再内存过滤() {
        candidates(sub(NO_JITTER_ID, "TV", 1, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        service().run();

        // 「ACTIVE + 开着开关 + 有 MISSING 集」三个条件都在 SQL 里，
        // 追完的老剧不该每轮都被捞出来再各查一次集表
        verify(subscriptionService, never()).listActive();
        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
    }

    @Test
    void 没有启用索引器_整轮跳过() {
        when(subscriptionService.listAutoSearchCandidates())
                .thenReturn(List.of(sub(NO_JITTER_ID, "TV", 1, null)));
        when(indexerService.listEnabled()).thenReturn(List.of());
        when(filterConfigService.getConfig()).thenReturn(config(24));

        new AutoSearchService(subscriptionService, filterConfigService, searchSupplementService,
                indexerService, 0).run();

        verify(searchSupplementService, never()).searchAndPushMissing(NO_JITTER_ID);
    }

    // ---------- 到期判断 ----------

    @Test
    void 从未搜索过_视为到期_发起搜索() {
        candidates(sub(NO_JITTER_ID, "TV", 1, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        service().run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
    }

    @Test
    void 未到周期_跳过() {
        candidates(sub(NO_JITTER_ID, "TV", 1, hoursAgo(1)));
        when(filterConfigService.getConfig()).thenReturn(config(24));

        service().run();

        verify(searchSupplementService, never()).searchAndPushMissing(NO_JITTER_ID);
    }

    @Test
    void 已过周期_发起搜索() {
        candidates(sub(NO_JITTER_ID, "TV", 1, hoursAgo(25)));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        service().run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
    }

    @Test
    void 全局周期未配置_默认24小时() {
        candidates(sub(NO_JITTER_ID, "TV", 1, hoursAgo(25)));
        when(filterConfigService.getConfig()).thenReturn(config(null));
        pushed(NO_JITTER_ID);

        service().run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
    }

    @Test
    void 到期时刻按id向后抖动_实际周期不会短于配置值() {
        // id=10 → 抖动 +10%，24 小时的周期实际是 26.4 小时；25 小时还不到期。
        // 抖动的意义：首次启动时全体订阅同时到期，串行跑完后 last_search_time 又几乎相同，
        // 一个周期后再次抱团——这个抱团不会自己散开，而抖动只向后偏移，
        // 保证实际周期不会比用户配的更短
        candidates(sub(10, "TV", 1, hoursAgo(25)));
        when(filterConfigService.getConfig()).thenReturn(config(24));

        service().run();

        verify(searchSupplementService, never()).searchAndPushMissing(10);
    }

    @Test
    void 抖动是按id确定的_不同订阅错开到期() {
        // 同样是 25 小时前搜过：抖动为 0 的那条到期，+10% 的那条还没到
        candidates(sub(NO_JITTER_ID, "TV", 1, hoursAgo(25)), sub(10, "TV", 1, hoursAgo(25)));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        service().run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
        verify(searchSupplementService, never()).searchAndPushMissing(10);
    }

    // ---------- 落空退避 ----------

    @Test
    void 连续落空一轮后_周期翻倍() {
        // 片源确实不存在的老剧不该永远每 24 小时打满一整轮索引器请求
        PtSubscriptionPlus s = sub(NO_JITTER_ID, "TV", 1, hoursAgo(30));
        s.setLastAutoSearchNoResult(1);
        candidates(s);
        when(filterConfigService.getConfig()).thenReturn(config(24));

        service().run();

        verify(searchSupplementService, never()).searchAndPushMissing(NO_JITTER_ID);
    }

    @Test
    void 退避到期后仍会再试() {
        PtSubscriptionPlus s = sub(NO_JITTER_ID, "TV", 1, hoursAgo(50));
        s.setLastAutoSearchNoResult(1);
        candidates(s);
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        service().run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
    }

    @Test
    void 退避有上限_连续落空很多轮也至少一周试一次() {
        PtSubscriptionPlus s = sub(NO_JITTER_ID, "TV", 1, hoursAgo(24 * 7 + 1));
        s.setLastAutoSearchNoResult(99);
        candidates(s);
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        service().run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
    }

    // ---------- 单轮预算 ----------

    @Test
    void 单轮预算耗尽_剩余订阅留到下一轮() {
        // 第一条订阅搜了 30ms、超掉 10ms 的预算，第二条在开始前就被挡下。
        // 被挡下的订阅 last_search_time 没被改动，下轮心跳照样判定到期，因此不会被饿死；
        // 预算只在每条订阅开始前检查，已经开工的那条不会被打断（软上限，不是超时）
        candidates(sub(NO_JITTER_ID, "TV", 1, null), sub(NO_JITTER_ID + 21, "TV", 1, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(NO_JITTER_ID)).thenAnswer(invocation -> {
            Thread.sleep(30);
            return new SearchAndPushSummary(false, true, 0);
        });

        service(10L).run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
        verify(searchSupplementService, never()).searchAndPushMissing(NO_JITTER_ID + 21);
    }

    @Test
    void 预算充足_全部到期订阅都搜() {
        candidates(sub(NO_JITTER_ID, "TV", 1, null), sub(NO_JITTER_ID + 21, "TV", 1, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);
        pushed(NO_JITTER_ID + 21);

        service(600_000L).run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID + 21);
    }

    // ---------- 逐条隔离 ----------

    @Test
    void 单个订阅搜索抛异常_不影响其他订阅() {
        candidates(sub(NO_JITTER_ID, "TV", 1, null), sub(NO_JITTER_ID + 21, "TV", 1, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(NO_JITTER_ID)).thenThrow(new RuntimeException("boom"));
        pushed(NO_JITTER_ID + 21);

        service().run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID + 21);
    }

    @Test
    void 电影订阅_照常发起搜索() {
        candidates(sub(NO_JITTER_ID, "MOVIE", 0, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        service().run();

        verify(searchSupplementService).searchAndPushMissing(NO_JITTER_ID);
    }

    @Test
    void 无缺集或订阅不可搜_跳过不更新任何标记() {
        candidates(sub(NO_JITTER_ID, "TV", 1, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(NO_JITTER_ID)).thenReturn(SearchAndPushSummary.skip());

        service().run();

        // 跳过既不是"落空"也不是"命中"，不该覆盖上一次真正搜索留下的状态
        verify(subscriptionService, never()).updateAutoSearchMissState(any(), anyInt(), any());
    }

    @Test
    void 季包未命中但补到散集_视为命中不通知() {
        candidates(sub(NO_JITTER_ID, "TV", 1, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        when(searchSupplementService.searchAndPushMissing(NO_JITTER_ID))
                .thenReturn(new SearchAndPushSummary(false, false, 3));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()), never());
        }
    }

    // ---------- 落空通知去重 ----------

    @Test
    void 首次落空_发一次通知并记录落空次数与原因指纹() {
        PtSubscriptionPlus s = sub(NO_JITTER_ID, "TV", 1, null);
        candidates(s);
        when(filterConfigService.getConfig()).thenReturn(config(24));
        missed(NO_JITTER_ID, null);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            // 补搜落空走 SUBSCRIPTION_SEARCH 而不是 GENERAL：后者是索引器故障、复制超时
            // 那类系统告警，混在一起时用户想单独关掉补搜提醒做不到。
            // 文案末尾要报出下次重试的间隔——退避之后实际周期不再等于用户配的那个值
            tg.verify(() -> TgHelper.sendMsg(eq(NotificationType.SUBSCRIPTION_SEARCH),
                    argThat(m -> m.contains("未找到可用资源") && m.contains("小时后再试")), any()));
            // 只更新这两列，绝不整实体写回——那会把本次搜索刚写入的 last_search_time
            // 覆盖成本轮开始时的旧值，让订阅永远"已到期"、每次心跳都重搜一遍
            verify(subscriptionService).updateAutoSearchMissState(NO_JITTER_ID, 1, "NO_CANDIDATE");
            verify(subscriptionService, never()).updateById(any(PtSubscriptionPlus.class));
        }
    }

    @Test
    void 连续落空且原因不变_不重复通知但累加次数() {
        PtSubscriptionPlus s = sub(NO_JITTER_ID, "TV", 1, hoursAgo(100));
        s.setLastAutoSearchNoResult(1);
        s.setLastAutoSearchRejectSign("NOT_FREE");
        candidates(s);
        when(filterConfigService.getConfig()).thenReturn(config(24));
        missed(NO_JITTER_ID, "NOT_FREE");

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()), never());
            // 与旧实现的差别：仍然写库。退避靠这个计数，不累加就永远停在第一档
            verify(subscriptionService).updateAutoSearchMissState(NO_JITTER_ID, 2, "NOT_FREE");
        }
    }

    @Test
    void 落空原因种类变了_再通知一次() {
        // 上次是「压根没搜到候选」，用户照着改了关键词，这次变成「候选全被过滤规则淘汰」——
        // 处置方向完全反过来了，只按"上次是否已落空"去重会把这次翻转吃掉
        PtSubscriptionPlus s = sub(NO_JITTER_ID, "TV", 1, hoursAgo(100));
        s.setLastAutoSearchNoResult(1);
        s.setLastAutoSearchRejectSign("NO_CANDIDATE");
        candidates(s);
        when(filterConfigService.getConfig()).thenReturn(config(24));
        missed(NO_JITTER_ID, "NOT_FREE");

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            tg.verify(() -> TgHelper.sendMsg(eq(NotificationType.SUBSCRIPTION_SEARCH),
                    argThat(m -> m.contains("过滤规则")), any()));
            verify(subscriptionService).updateAutoSearchMissState(NO_JITTER_ID, 2, "NOT_FREE");
        }
    }

    @Test
    void 落空后再次命中_重置次数与指纹且不发通知() {
        PtSubscriptionPlus s = sub(NO_JITTER_ID, "TV", 1, hoursAgo(100));
        s.setLastAutoSearchNoResult(2);
        s.setLastAutoSearchRejectSign("NOT_FREE");
        candidates(s);
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().run();

            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()), never());
            verify(subscriptionService).updateAutoSearchMissState(NO_JITTER_ID, 0, null);
        }
    }

    @Test
    void 一直命中_不必每轮写库() {
        candidates(sub(NO_JITTER_ID, "TV", 1, null));
        when(filterConfigService.getConfig()).thenReturn(config(24));
        pushed(NO_JITTER_ID);

        service().run();

        verify(subscriptionService, never()).updateAutoSearchMissState(any(), anyInt(), any());
    }
}
