package com.osr.openliststrm.chat;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.osr.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天指令里企微与 TG 共用、且判错了不报错只会悄悄做错事的几条分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtChatCommandServiceTest {

    @Mock private TmdbSearchService tmdbSearchService;
    @Mock private SubscriptionService subscriptionBiz;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private SubscriptionSearchOnCreateTrigger searchOnCreateTrigger;
    @Mock private SearchSupplementService searchSupplementService;
    @Spy private ChatSessionStore sessionStore = new ChatSessionStore();

    @InjectMocks private PtChatCommandService service;

    private final BlockingQueue<String> later = new LinkedBlockingQueue<>();

    private ChatUser user(long sysUserId) {
        return new ChatUser("tg:1", sysUserId, "账号", later::add);
    }

    private static TmdbSearchItem movie(String tmdbId, String title) {
        TmdbSearchItem item = new TmdbSearchItem();
        item.setTmdbId(tmdbId);
        item.setMediaType(TmdbSearchService.TYPE_MOVIE);
        item.setTitle(title);
        return item;
    }

    private static PtSubscriptionPlus sub(int id, String status, Long owner) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setTitle("三体");
        sub.setMediaType(TmdbSearchService.TYPE_TV);
        sub.setSeason(1);
        sub.setStatus(status);
        sub.setOwnerUserId(owner);
        return sub;
    }

    /** 从回复的按钮里找出点下去会发送的指令 */
    private static String buttonCommand(ChatReply reply, String label) {
        return reply.buttons().stream().flatMap(List::stream)
                .filter(b -> b.label().contains(label))
                .findFirst().orElseThrow(() -> new AssertionError("没有按钮：" + label + "，实际：" + reply.buttons()))
                .command();
    }

    /**
     * TG 旧消息上的按钮一直点得到。用户搜过《流浪地球》后又搜了《满江红》，回头点第一轮的
     * 「1」，若按裸序号处理，会订上第二轮的第 1 项——一部他没选的片。
     */
    @Test
    void 点上一轮搜索留下的按钮_判过期且不建订阅() {
        when(tmdbSearchService.search(TmdbSearchService.TYPE_MOVIE, "流浪地球"))
                .thenReturn(List.of(movie("1", "流浪地球")));
        when(tmdbSearchService.search(TmdbSearchService.TYPE_MOVIE, "满江红"))
                .thenReturn(List.of(movie("2", "满江红")));

        ChatReply first = service.handle(user(1L), "订阅电影 流浪地球");
        String staleButton = buttonCommand(first, "流浪地球");
        service.handle(user(1L), "订阅电影 满江红");

        ChatReply reply = service.handle(user(1L), staleButton);

        assertTrue(reply.text().contains("过期"), reply.text());
        verify(subscriptionBiz, never()).subscribe(any());
    }

    @Test
    void 点当前这一轮的按钮_订阅所选作品并归属当前用户() {
        when(tmdbSearchService.search(TmdbSearchService.TYPE_MOVIE, "流浪地球"))
                .thenReturn(List.of(movie("1", "流浪地球"), movie("2", "流浪地球2")));
        PtSubscriptionPlus created = sub(9, SubscriptionService.STATUS_ACTIVE, 1L);
        created.setMediaType(SubscriptionService.TYPE_MOVIE);
        when(subscriptionBiz.subscribe(any())).thenReturn(created);

        ChatReply search = service.handle(user(1L), "订阅电影 流浪地球");
        ChatReply reply = service.handle(user(1L), buttonCommand(search, "流浪地球2"));

        ArgumentCaptor<SubscribeRequest> captor = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(subscriptionBiz).subscribe(captor.capture());
        assertEquals("2", captor.getValue().getTmdbId());
        assertEquals(1L, captor.getValue().getOwnerUserId());
        assertTrue(reply.text().contains("已订阅"), reply.text());
    }

    /** 企微里没有按钮，照旧回序号也得能选 */
    @Test
    void 手打序号_仍按当前会话选择() {
        when(tmdbSearchService.search(TmdbSearchService.TYPE_MOVIE, "流浪地球"))
                .thenReturn(List.of(movie("1", "流浪地球")));
        PtSubscriptionPlus created = sub(9, SubscriptionService.STATUS_ACTIVE, 1L);
        when(subscriptionBiz.subscribe(any())).thenReturn(created);

        service.handle(user(1L), "订阅电影 流浪地球");
        service.handle(user(1L), "1");

        verify(subscriptionBiz).subscribe(any());
    }

    /**
     * 补搜要跑几分钟，TG 按钮连点几下时不能并发搜几轮、把同一资源推几次；
     * 搜完要经 laterReply 补发结果，且结果文案要带上真实的落空原因。
     */
    @Test
    void 补搜_异步执行_重复触发被拦_结束后补发结果() throws Exception {
        when(subscriptionService.getById(3)).thenReturn(sub(3, SubscriptionService.STATUS_ACTIVE, null));
        CountDownLatch release = new CountDownLatch(1);
        SearchAndPushSummary summary = new SearchAndPushSummary(false, false, 0, "5 个候选被过滤规则淘汰", "sig");
        when(searchSupplementService.searchAndPushMissing(3)).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return summary;
        });

        String first = service.handle(user(1L), "补搜 3").text();
        String second = service.handle(user(1L), "补搜 3").text();
        release.countDown();
        String result = later.poll(5, TimeUnit.SECONDS);

        assertTrue(first.contains("已开始补搜"), first);
        assertTrue(second.contains("正在补搜"), second);
        assertNotNull(result, "搜完应补发结果");
        assertTrue(result.contains("5 个候选被过滤规则淘汰"), result);
        verify(searchSupplementService, times(1)).searchAndPushMissing(3);
    }

    @Test
    void 补搜_上一轮结束后可以再次触发() throws Exception {
        when(subscriptionService.getById(3)).thenReturn(sub(3, SubscriptionService.STATUS_ACTIVE, null));
        when(searchSupplementService.searchAndPushMissing(3)).thenReturn(SearchAndPushSummary.skip());

        service.handle(user(1L), "补搜 3");
        assertNotNull(later.poll(5, TimeUnit.SECONDS));
        service.handle(user(1L), "补搜 3");
        assertNotNull(later.poll(5, TimeUnit.SECONDS));

        verify(searchSupplementService, times(2)).searchAndPushMissing(3);
    }

    @Test
    void 补搜_别人的订阅_与不存在回同一句且不搜() {
        when(subscriptionService.getById(3)).thenReturn(sub(3, SubscriptionService.STATUS_ACTIVE, 200L));

        String reply = service.handle(user(100L), "补搜 3").text();

        assertTrue(reply.contains("不存在或无权访问"), reply);
        verify(searchSupplementService, never()).searchAndPushMissing(any());
    }

    @Test
    void 补搜_没有启用的索引器_直接提示() {
        when(subscriptionService.getById(3)).thenReturn(sub(3, SubscriptionService.STATUS_ACTIVE, null));
        when(searchSupplementService.hasNoEnabledIndexer()).thenReturn(true);

        String reply = service.handle(user(1L), "补搜 3").text();

        assertTrue(reply.contains("索引器"), reply);
        verify(searchSupplementService, never()).searchAndPushMissing(any());
    }

    @Test
    void 进度_有缺集的订阅中给出补搜与暂停按钮_已暂停只给恢复() {
        SubscriptionProgress progress = new SubscriptionProgress();
        progress.setTotalEpisodes(10);
        progress.setMissingEpisodes(List.of(4, 5));
        when(subscriptionBiz.getProgress(3)).thenReturn(progress);

        when(subscriptionService.getById(3)).thenReturn(sub(3, SubscriptionService.STATUS_ACTIVE, null));
        ChatReply active = service.handle(user(1L), "进度 3");
        assertEquals("补搜 3", buttonCommand(active, "补搜"));
        assertEquals("暂停 3", buttonCommand(active, "暂停"));

        when(subscriptionService.getById(3)).thenReturn(sub(3, SubscriptionService.STATUS_PAUSED, null));
        ChatReply paused = service.handle(user(1L), "进度 3");
        assertEquals("恢复 3", buttonCommand(paused, "恢复"));
        assertTrue(paused.buttons().stream().flatMap(List::stream).noneMatch(b -> b.label().contains("补搜")));
    }

    /** 按钮只是快捷方式：正文必须自给自足，企微用户只看得到正文 */
    @Test
    void 搜索候选_正文里也列出了序号() {
        when(tmdbSearchService.search(anyString(), anyString())).thenReturn(List.of(movie("1", "流浪地球")));

        ChatReply reply = service.handle(user(1L), "订阅电影 流浪地球");

        assertTrue(reply.text().contains("1. 流浪地球"), reply.text());
    }
}
