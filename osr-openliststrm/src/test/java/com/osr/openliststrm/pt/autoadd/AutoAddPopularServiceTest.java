package com.osr.openliststrm.pt.autoadd;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.autoadd.dto.AutoAddRunResult;
import com.osr.openliststrm.pt.autoadd.source.PopularItem;
import com.osr.openliststrm.pt.autoadd.source.PopularSource;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉住「热门自动订阅建完订阅要发起一次补搜」。
 * <p>
 * 这一条曾经漏了，而<b>漏掉它不会让任何东西变红、也不会在日志里留下痕迹</b>：规则照常跑完、
 * 执行日志里一排 ADDED，只是那些订阅的进度恒为 0。因为 {@code auto_search} 的库默认是 '0'，
 * 定期补搜的候选 SQL 要求那个开关开着，于是自动订进来的剧一条主动搜索路径都不走，
 * 只剩 RSS 轮询碰运气——而榜单里的热门剧多半已经播了几集，历史集的种子早出窗口了。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoAddPopularServiceTest {

    @Mock
    private PopularSource source;

    @Mock
    private IPtAutoAddRulePlusService ruleService;

    @Mock
    private IPtAutoAddLogPlusService logService;

    @Mock
    private IPtSubscriptionPlusService subscriptionPlusService;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private TmdbSearchService tmdbSearchService;

    @Mock
    private PopularItemResolver resolver;

    @Mock
    private SubscriptionSearchOnCreateTrigger searchOnCreateTrigger;

    @InjectMocks
    private AutoAddPopularService service;

    @BeforeEach
    void setUp() {
        // sources 是 List<PopularSource>，@InjectMocks 填不进去
        ReflectionTestUtils.setField(service, "sources", List.of(source));
        when(source.supports(any())).thenReturn(true);
        when(source.fetch(any())).thenReturn(List.of(item()));
        // 没有同作品同季的存量订阅
        when(subscriptionPlusService.count(any(Wrapper.class))).thenReturn(0L);
    }

    private PtAutoAddRulePlus rule() {
        PtAutoAddRulePlus rule = new PtAutoAddRulePlus();
        rule.setId(1);
        rule.setName("TMDb 热门剧集");
        rule.setSource("TMDB_POPULAR");
        rule.setMediaType("TV");
        rule.setMaxAddPerRun(5);
        return rule;
    }

    /** 已带 tmdbId 与季号：不走补全，也不打 TMDb 查最新季 */
    private PopularItem item() {
        PopularItem item = new PopularItem();
        item.setTmdbId("1399");
        item.setMediaType("TV");
        item.setTitle("权力的游戏");
        item.setSeasonNumber(1);
        return item;
    }

    private PtSubscriptionPlus sub(String status) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(42);
        sub.setTitle("权力的游戏");
        sub.setSeason(1);
        sub.setStatus(status);
        return sub;
    }

    @Test
    void 建订阅成功后应发起一次建订阅补搜() {
        when(subscriptionService.subscribe(any(SubscribeRequest.class)))
                .thenReturn(sub(SubscriptionService.STATUS_ACTIVE));

        AutoAddRunResult result = service.runRule(rule());

        assertEquals(1, result.getAddedCount());
        verify(searchOnCreateTrigger).triggerAsync(eq(42));
    }

    /**
     * 建订阅时已经对过一次账，全部集都在库的订阅直接是 COMPLETED——没有可补的东西，
     * 补搜只会白打一轮索引器请求。
     */
    @Test
    void 订阅建完就是已完成时不补搜() {
        when(subscriptionService.subscribe(any(SubscribeRequest.class)))
                .thenReturn(sub("COMPLETED"));

        service.runRule(rule());

        verify(searchOnCreateTrigger, never()).triggerAsync(any());
    }

    @Test
    void 建订阅失败时不补搜() {
        when(subscriptionService.subscribe(any(SubscribeRequest.class)))
                .thenThrow(new IllegalArgumentException("已存在该订阅"));

        AutoAddRunResult result = service.runRule(rule());

        assertEquals(0, result.getAddedCount());
        assertEquals(1, result.getFailedCount());
        verify(searchOnCreateTrigger, never()).triggerAsync(any());
    }

    /**
     * 触发补搜失败不能把这条翻成 FAILED：订阅此时已经建成功了，而 FAILED 那条日志
     * 是用来解释「这轮为什么没加」的。
     */
    @Test
    void 补搜触发失败不影响订阅已新增的结论() {
        when(subscriptionService.subscribe(any(SubscribeRequest.class)))
                .thenReturn(sub(SubscriptionService.STATUS_ACTIVE));
        doThrow(new IllegalStateException("调度器已关闭")).when(searchOnCreateTrigger).triggerAsync(any());

        AutoAddRunResult result = service.runRule(rule());

        assertEquals(1, result.getAddedCount());
        assertEquals(0, result.getFailedCount());
    }

    // ---------------------------------------------------------------------------------------------
    // 删掉的不加回、复用上轮匹配、日志去重、只写 last_run_time、并发互斥
    // ---------------------------------------------------------------------------------------------

    /**
     * 用户删掉自动订进来的订阅，表达的就是「不要这部」。只查订阅表的话它还挂在榜上，
     * 下一轮就原样订回来并重新开始下载。
     */
    @Test
    void 自动订过又被删掉的不再加回() {
        when(logService.everAdded("1399", "TV", 1)).thenReturn(true);

        AutoAddRunResult result = service.runRule(rule());

        assertEquals(0, result.getAddedCount());
        assertEquals(1, result.getSkippedCount());
        verify(subscriptionService, never()).subscribe(any());
        ArgumentCaptor<PtAutoAddLogPlus> saved = ArgumentCaptor.forClass(PtAutoAddLogPlus.class);
        verify(logService).save(saved.capture());
        assertEquals(AutoAddPopularService.RESULT_SKIPPED_REMOVED, saved.getValue().getResult());
    }

    @Test
    void 跳过类结果已记过时不重复写日志() {
        when(subscriptionPlusService.count(any(Wrapper.class))).thenReturn(1L);
        when(logService.alreadyLogged(any())).thenReturn(true);

        service.runRule(rule());

        verify(logService, never()).save(any());
    }

    /** 豆瓣条目上一轮已经匹配到 tmdbId 且那一季已订阅：不该再按标题搜一遍 TMDb */
    @Test
    void 豆瓣条目上一轮已匹配且已订阅时不再搜TMDb() {
        when(source.fetch(any())).thenReturn(List.of(doubanItem()));
        PtAutoAddLogPlus last = new PtAutoAddLogPlus();
        last.setTmdbId("1399");
        last.setSeason(1);
        last.setResult(AutoAddPopularService.RESULT_ADDED);
        when(logService.latestBySourceItem("26794435", "TV")).thenReturn(last);
        when(subscriptionPlusService.count(any(Wrapper.class))).thenReturn(1L);

        AutoAddRunResult result = service.runRule(rule());

        assertEquals(1, result.getSkippedCount());
        verify(resolver, never()).resolve(any(), any());
        verify(logService, never()).save(any());
    }

    @Test
    void 豆瓣条目近期未匹配上时不重搜() {
        when(source.fetch(any())).thenReturn(List.of(doubanItem()));
        PtAutoAddLogPlus last = new PtAutoAddLogPlus();
        last.setResult(AutoAddPopularService.RESULT_SKIPPED_NO_MATCH);
        last.setCreateTime(LocalDateTime.now().minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        when(logService.latestBySourceItem("26794435", "TV")).thenReturn(last);

        service.runRule(rule());

        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    void 豆瓣条目未匹配超过重试期后重搜() {
        when(source.fetch(any())).thenReturn(List.of(doubanItem()));
        PtAutoAddLogPlus last = new PtAutoAddLogPlus();
        last.setResult(AutoAddPopularService.RESULT_SKIPPED_NO_MATCH);
        last.setCreateTime(LocalDateTime.now().minusDays(AutoAddPopularService.NO_MATCH_RETRY_DAYS + 1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        when(logService.latestBySourceItem("26794435", "TV")).thenReturn(last);
        when(resolver.resolve(any(), any())).thenReturn("TMDb 未搜到标题一致的剧集");

        service.runRule(rule());

        verify(resolver).resolve(any(), eq("TV"));
    }

    /** 整条 updateById 会把执行开始时读到的旧规则写回去，覆盖执行期间用户在页面上的修改 */
    @Test
    void 执行完只更新上次执行时间这一列() {
        when(subscriptionService.subscribe(any(SubscribeRequest.class)))
                .thenReturn(sub(SubscriptionService.STATUS_ACTIVE));

        service.runRule(rule());

        verify(ruleService, never()).updateById(any());
        verify(ruleService).update(any(Wrapper.class));
    }

    @Test
    void 拉榜失败时不推进执行时间并写一条执行日志() {
        when(source.fetch(any())).thenThrow(new IllegalStateException("RSSHub 返回 503"));

        AutoAddRunResult result = service.runRule(rule());

        assertEquals("RSSHub 返回 503", result.getFetchError());
        verify(ruleService, never()).update(any(Wrapper.class));
        ArgumentCaptor<PtAutoAddLogPlus> saved = ArgumentCaptor.forClass(PtAutoAddLogPlus.class);
        verify(logService).save(saved.capture());
        assertEquals(AutoAddPopularService.RESULT_FETCH_FAILED, saved.getValue().getResult());
    }

    @Test
    void 连续拉榜失败只在开始时写执行日志() {
        when(source.fetch(any())).thenThrow(new IllegalStateException("RSSHub 返回 503"));

        service.runRule(rule());
        service.runRule(rule());
        service.runRule(rule());

        verify(logService, times(1)).save(any());
    }

    /** 「立即执行」与定时任务撞上时，后到的那份不许再跑：两份都建订阅，后者会被记成 FAILED */
    @Test
    void 同一条规则执行中时再次执行被拒绝() {
        PtAutoAddRulePlus rule = rule();
        AtomicReference<Throwable> reentrant = new AtomicReference<>();
        when(source.fetch(any())).thenAnswer(inv -> {
            reentrant.set(assertThrows(IllegalStateException.class, () -> service.runRule(rule)));
            return List.of();
        });

        service.runRule(rule);

        assertTrue(reentrant.get().getMessage().contains("正在执行中"));
        // 跑完要释放，否则这条规则从此再也跑不了。用 doReturn：when(source.fetch(..)) 会先把上面那个 answer 调一遍
        doReturn(List.of()).when(source).fetch(any());
        service.runRule(rule);
    }

    @Test
    void 排除类型含非法值时忽略该值而不是整条规则失败() {
        assertEquals(Set.of(16, 99), service.parseGenreExclude("16, 动画,99,"));
    }

    private PopularItem doubanItem() {
        PopularItem item = new PopularItem();
        item.setDoubanId("26794435");
        item.setMediaType("TV");
        item.setTitle("权力的游戏");
        return item;
    }
}
