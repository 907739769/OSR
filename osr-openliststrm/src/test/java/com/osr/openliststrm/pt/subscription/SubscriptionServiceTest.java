package com.osr.openliststrm.pt.subscription;

import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.media.IMediaServerClient;
import com.osr.openliststrm.pt.media.MediaServerClientFactory;
import com.osr.openliststrm.pt.subscription.dto.BatchOperationResult;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.osr.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceTest {

    @Mock
    private IPtSubscriptionPlusService subscriptionService;
    @Mock
    private IPtSubscriptionEpisodePlusService episodeService;
    @Mock
    private IPtMediaServerPlusService mediaServerService;
    @Mock
    private MediaServerClientFactory mediaServerClientFactory;
    @Mock
    private IMediaServerClient mediaServerClient;
    @Mock
    private TmdbSearchService tmdbSearchService;

    @InjectMocks
    private SubscriptionService service;

    private TmdbSearchItem detail(String title, String year) {
        return detail(title, title, year);
    }

    private TmdbSearchItem detail(String title, String originalTitle, String year) {
        TmdbSearchItem item = new TmdbSearchItem();
        item.setTitle(title);
        item.setOriginalTitle(originalTitle);
        item.setYear(year);
        item.setPosterPath("/p.jpg");
        return item;
    }

    /** 让 save 给实体塞上 id，模拟自增主键回填 */
    private void stubSaveAssignsId(int id) {
        doAnswer(inv -> {
            ((PtSubscriptionPlus) inv.getArgument(0)).setId(id);
            return true;
        }).when(subscriptionService).save(any(PtSubscriptionPlus.class));
    }

    private void stubEmbyConfigured() {
        PtMediaServerPlus server = new PtMediaServerPlus();
        server.setId(1);
        server.setType("EMBY");
        when(mediaServerService.getActive()).thenReturn(server);
        when(mediaServerClientFactory.get(any())).thenReturn(mediaServerClient);
    }

    private SubscribeRequest tvRequest() {
        SubscribeRequest req = new SubscribeRequest();
        req.setTmdbId("1396");
        req.setMediaType("TV");
        req.setSeason(1);
        return req;
    }

    private SubscribeRequest movieRequest() {
        SubscribeRequest req = new SubscribeRequest();
        req.setTmdbId("550");
        req.setMediaType("MOVIE");
        return req;
    }

    // ---------- 建订阅：剧集 ----------

    @Test
    void subscribe_剧集_按总集数生成集行并用Emby初始化状态() throws Exception {
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(detail("绝命毒师", "2008"));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(7);
        stubSaveAssignsId(10);
        stubEmbyConfigured();
        when(mediaServerClient.listEpisodes(any(), anyString(), anyInt())).thenReturn(Set.of(1, 2, 5));

        service.subscribe(tvRequest());

        ArgumentCaptor<List<PtSubscriptionEpisodePlus>> captor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).saveBatch(captor.capture());
        List<PtSubscriptionEpisodePlus> episodes = captor.getValue();

        assertEquals(7, episodes.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7),
                episodes.stream().map(PtSubscriptionEpisodePlus::getEpisode).toList());
        assertEquals(List.of("IN_LIBRARY", "IN_LIBRARY", "MISSING", "MISSING", "IN_LIBRARY", "MISSING", "MISSING"),
                episodes.stream().map(PtSubscriptionEpisodePlus::getState).toList());
        assertTrue(episodes.stream().allMatch(e -> e.getSubId() == 10));
    }

    @Test
    void subscribe_剧集_落库字段正确() throws Exception {
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(detail("绝命毒师", "Breaking Bad", "2008"));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(7);
        stubSaveAssignsId(10);
        when(mediaServerService.getActive()).thenReturn(null);

        service.subscribe(tvRequest());

        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).save(captor.capture());
        PtSubscriptionPlus sub = captor.getValue();

        assertEquals("1396", sub.getTmdbId());
        assertEquals("TV", sub.getMediaType());
        assertEquals("绝命毒师", sub.getTitle());
        assertEquals("Breaking Bad", sub.getOriginalTitle());
        assertEquals("2008", sub.getYear());
        assertEquals(1, sub.getSeason());
        assertEquals(7, sub.getTotalEpisodes());
        assertEquals("ACTIVE", sub.getStatus());
        assertEquals("/p.jpg", sub.getPosterPath());
    }

    @Test
    void subscribe_剧集_全部已入库_直接置为已完成() throws Exception {
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(detail("剧", "2020"));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(3);
        stubSaveAssignsId(11);
        stubEmbyConfigured();
        when(mediaServerClient.listEpisodes(any(), anyString(), anyInt())).thenReturn(Set.of(1, 2, 3));

        service.subscribe(tvRequest());

        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).save(captor.capture());
        assertEquals("COMPLETED", captor.getValue().getStatus());
    }

    // ---------- 建订阅：电影 ----------

    @Test
    void subscribe_电影_季号0总集数1唯一集行为0() throws Exception {
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(detail("搏击俱乐部", "1999"));
        stubSaveAssignsId(20);
        stubEmbyConfigured();
        when(mediaServerClient.hasMovie(any(), anyString())).thenReturn(false);

        service.subscribe(movieRequest());

        ArgumentCaptor<PtSubscriptionPlus> subCaptor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).save(subCaptor.capture());
        assertEquals("MOVIE", subCaptor.getValue().getMediaType());
        assertEquals(0, subCaptor.getValue().getSeason());
        assertEquals(1, subCaptor.getValue().getTotalEpisodes());

        ArgumentCaptor<List<PtSubscriptionEpisodePlus>> epCaptor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).saveBatch(epCaptor.capture());
        assertEquals(1, epCaptor.getValue().size());
        assertEquals(0, epCaptor.getValue().get(0).getEpisode());
        assertEquals("MISSING", epCaptor.getValue().get(0).getState());
    }

    @Test
    void subscribe_落库时带上TMDb返回的imdbId() throws Exception {
        TmdbSearchItem d = detail("搏击俱乐部", "1999");
        d.setImdbId("tt0137523");
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(d);
        stubSaveAssignsId(70);
        when(mediaServerService.getActive()).thenReturn(null);

        service.subscribe(movieRequest());

        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).save(captor.capture());
        assertEquals("tt0137523", captor.getValue().getImdbId());
    }

    @Test
    void subscribe_电影_不调用剧集的总集数接口() throws Exception {
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(detail("片", "1999"));
        stubSaveAssignsId(21);
        when(mediaServerService.getActive()).thenReturn(null);

        service.subscribe(movieRequest());

        verify(tmdbSearchService, never()).getSeasonEpisodeCount(anyString(), anyInt());
    }

    @Test
    void subscribe_电影已在库_置为已完成() throws Exception {
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(detail("片", "1999"));
        stubSaveAssignsId(22);
        stubEmbyConfigured();
        when(mediaServerClient.hasMovie(any(), anyString())).thenReturn(true);

        service.subscribe(movieRequest());

        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).save(captor.capture());
        assertEquals("COMPLETED", captor.getValue().getStatus());
    }

    // ---------- Emby 不可用 ----------

    @Test
    void subscribe_未配置媒体服务器_全部按缺失处理且订阅照常建成() throws Exception {
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(detail("剧", "2020"));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(3);
        stubSaveAssignsId(30);
        when(mediaServerService.getActive()).thenReturn(null);

        service.subscribe(tvRequest());

        ArgumentCaptor<List<PtSubscriptionEpisodePlus>> captor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).saveBatch(captor.capture());
        assertTrue(captor.getValue().stream().allMatch(e -> "MISSING".equals(e.getState())));
    }

    @Test
    void subscribe_Emby查询抛IO异常_全部按缺失处理而非让建订阅失败() throws Exception {
        when(tmdbSearchService.getDetail(anyString(), anyString())).thenReturn(detail("剧", "2020"));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(3);
        stubSaveAssignsId(31);
        stubEmbyConfigured();
        when(mediaServerClient.listEpisodes(any(), anyString(), anyInt())).thenThrow(new IOException("connection refused"));

        service.subscribe(tvRequest());

        ArgumentCaptor<List<PtSubscriptionEpisodePlus>> captor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).saveBatch(captor.capture());
        assertTrue(captor.getValue().stream().allMatch(e -> "MISSING".equals(e.getState())));
    }

    // ---------- 入参校验 ----------

    @Test
    void subscribe_剧集未指定季_抛IllegalArgumentException() {
        SubscribeRequest req = tvRequest();
        req.setSeason(null);

        assertThrows(IllegalArgumentException.class, () -> service.subscribe(req));
    }

    @Test
    void subscribe_tmdbId为空_抛IllegalArgumentException() {
        SubscribeRequest req = tvRequest();
        req.setTmdbId("  ");

        assertThrows(IllegalArgumentException.class, () -> service.subscribe(req));
    }

    // ---------- 对账刷新 ----------

    @Test
    void refresh_把Emby已有的集升级为已入库() throws Exception {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(40);
        sub.setTmdbId("1396");
        sub.setMediaType("TV");
        sub.setSeason(1);
        sub.setTotalEpisodes(3);
        sub.setStatus("ACTIVE");
        when(subscriptionService.getById(40)).thenReturn(sub);
        when(episodeService.listBySubscription(40)).thenReturn(List.of(
                episode(1, "MISSING"), episode(2, "IN_FLIGHT"), episode(3, "MISSING")));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(3);
        stubEmbyConfigured();
        when(mediaServerClient.listEpisodes(any(), anyString(), anyInt())).thenReturn(Set.of(1, 2));

        service.refresh(40);

        ArgumentCaptor<List<PtSubscriptionEpisodePlus>> captor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).updateBatchById(captor.capture());
        assertEquals(List.of(1, 2), captor.getValue().stream().map(PtSubscriptionEpisodePlus::getEpisode).toList());
        assertTrue(captor.getValue().stream().allMatch(e -> "IN_LIBRARY".equals(e.getState())));
    }

    @Test
    void refresh_不把已入库降级回缺失() throws Exception {
        PtSubscriptionPlus sub = activeTv(41, 2);
        when(subscriptionService.getById(41)).thenReturn(sub);
        when(episodeService.listBySubscription(41)).thenReturn(List.of(
                episode(1, "IN_LIBRARY"), episode(2, "IN_LIBRARY")));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(2);
        stubEmbyConfigured();
        // 用户从 Emby 删了第 2 集
        when(mediaServerClient.listEpisodes(any(), anyString(), anyInt())).thenReturn(Set.of(1));

        service.refresh(41);

        // 只升级不降级：不应有任何更新
        verify(episodeService, never()).updateBatchById(any());
    }

    @Test
    void refresh_总集数增加_补齐新集行并把已完成的订阅改回订阅中() throws Exception {
        PtSubscriptionPlus sub = activeTv(42, 2);
        sub.setStatus("COMPLETED");
        when(subscriptionService.getById(42)).thenReturn(sub);
        when(episodeService.listBySubscription(42)).thenReturn(List.of(
                episode(1, "IN_LIBRARY"), episode(2, "IN_LIBRARY")));
        // TMDb 那边这一季从 2 集涨到 4 集
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(4);
        when(mediaServerService.getActive()).thenReturn(null);

        service.refresh(42);

        ArgumentCaptor<List<PtSubscriptionEpisodePlus>> captor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).saveBatch(captor.capture());
        assertEquals(List.of(3, 4), captor.getValue().stream().map(PtSubscriptionEpisodePlus::getEpisode).toList());

        ArgumentCaptor<PtSubscriptionPlus> subCaptor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).updateById(subCaptor.capture());
        assertEquals("ACTIVE", subCaptor.getValue().getStatus());
        assertEquals(4, subCaptor.getValue().getTotalEpisodes());
    }

    @Test
    void refresh_全部入库_订阅置为已完成() throws Exception {
        PtSubscriptionPlus sub = activeTv(43, 2);
        when(subscriptionService.getById(43)).thenReturn(sub);
        when(episodeService.listBySubscription(43)).thenReturn(List.of(
                episode(1, "IN_LIBRARY"), episode(2, "MISSING")));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(2);
        stubEmbyConfigured();
        when(mediaServerClient.listEpisodes(any(), anyString(), anyInt())).thenReturn(Set.of(1, 2));

        service.refresh(43);

        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).updateById(captor.capture());
        assertEquals("COMPLETED", captor.getValue().getStatus());
    }

    @Test
    void refresh_订阅不存在_抛IllegalArgumentException() {
        when(subscriptionService.getById(999)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.refresh(999));
    }

    @Test
    void refresh_暂停的订阅对账后仍是暂停() throws Exception {
        PtSubscriptionPlus sub = activeTv(44, 2);
        sub.setStatus("PAUSED");
        when(subscriptionService.getById(44)).thenReturn(sub);
        when(episodeService.listBySubscription(44)).thenReturn(List.of(
                episode(1, "MISSING"), episode(2, "MISSING")));
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(2);
        stubEmbyConfigured();
        // Emby 里全部已有
        when(mediaServerClient.listEpisodes(any(), anyString(), anyInt())).thenReturn(Set.of(1, 2));

        service.refresh(44);

        // 暂停是用户显式意图，对账不应把它悄悄推进成 COMPLETED
        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService, atMost(1)).updateById(captor.capture());
        if (!captor.getAllValues().isEmpty()) {
            assertEquals("PAUSED", captor.getValue().getStatus());
        }
    }

    @Test
    void refresh_暂停的订阅总集数增长也不被改回订阅中() throws Exception {
        PtSubscriptionPlus sub = activeTv(45, 2);
        sub.setStatus("PAUSED");
        when(subscriptionService.getById(45)).thenReturn(sub);
        when(episodeService.listBySubscription(45)).thenReturn(List.of(
                episode(1, "IN_LIBRARY"), episode(2, "IN_LIBRARY")));
        // TMDb 那边这一季从 2 集涨到 4 集
        when(tmdbSearchService.getSeasonEpisodeCount(anyString(), anyInt())).thenReturn(4);
        when(mediaServerService.getActive()).thenReturn(null);

        service.refresh(45);

        ArgumentCaptor<List<PtSubscriptionEpisodePlus>> epCaptor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).saveBatch(epCaptor.capture());
        assertEquals(List.of(3, 4), epCaptor.getValue().stream().map(PtSubscriptionEpisodePlus::getEpisode).toList());

        ArgumentCaptor<PtSubscriptionPlus> subCaptor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).updateById(subCaptor.capture());
        assertEquals("PAUSED", subCaptor.getValue().getStatus());
        assertEquals(4, subCaptor.getValue().getTotalEpisodes());
    }

    // ---------- 进度 ----------

    @Test
    void getProgress_统计已入库在途与缺集列表() {
        PtSubscriptionPlus sub = activeTv(50, 5);
        sub.setTitle("某剧");
        when(subscriptionService.getById(50)).thenReturn(sub);
        when(episodeService.listBySubscription(50)).thenReturn(List.of(
                episode(1, "IN_LIBRARY"), episode(2, "IN_LIBRARY"),
                episode(3, "MISSING"), episode(4, "IN_FLIGHT"), episode(5, "MISSING")));

        SubscriptionProgress progress = service.getProgress(50);

        assertEquals(5, progress.getTotalEpisodes());
        assertEquals(2, progress.getInLibraryCount());
        assertEquals(1, progress.getInFlightCount());
        assertEquals(List.of(3, 5), progress.getMissingEpisodes());
        assertEquals("某剧", progress.getTitle());
    }

    // ---------- 暂停恢复 ----------

    @Test
    void pause_把订阅置为暂停() {
        when(subscriptionService.getById(60)).thenReturn(activeTv(60, 1));

        service.pause(60);

        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).updateById(captor.capture());
        assertEquals("PAUSED", captor.getValue().getStatus());
    }

    @Test
    void resume_把订阅置回订阅中() {
        PtSubscriptionPlus sub = activeTv(61, 1);
        sub.setStatus("PAUSED");
        when(subscriptionService.getById(61)).thenReturn(sub);

        service.resume(61);

        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).updateById(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getStatus());
    }

    // ---------- 批量暂停/恢复 ----------

    @Test
    void pauseBatch_全部存在_成功数等于总数且逐条更新() {
        when(subscriptionService.getById(1)).thenReturn(activeTv(1, 1));
        when(subscriptionService.getById(2)).thenReturn(activeTv(2, 1));
        when(subscriptionService.getById(3)).thenReturn(activeTv(3, 1));

        BatchOperationResult result = service.pauseBatch(List.of(1, 2, 3));

        assertEquals(3, result.getSuccessCount());
        assertTrue(result.getFailedIds().isEmpty());
        verify(subscriptionService, times(3)).updateById(any());
    }

    @Test
    void pauseBatch_其中一个不存在_不中断其余条目() {
        when(subscriptionService.getById(1)).thenReturn(activeTv(1, 1));
        when(subscriptionService.getById(2)).thenReturn(null);
        when(subscriptionService.getById(3)).thenReturn(activeTv(3, 1));

        BatchOperationResult result = service.pauseBatch(List.of(1, 2, 3));

        assertEquals(2, result.getSuccessCount());
        assertEquals(List.of(2), result.getFailedIds());
        verify(subscriptionService, times(2)).updateById(any());
    }

    @Test
    void resumeBatch_其中一个不存在_不中断其余条目() {
        when(subscriptionService.getById(4)).thenReturn(activeTv(4, 1));
        when(subscriptionService.getById(5)).thenReturn(null);

        BatchOperationResult result = service.resumeBatch(List.of(4, 5));

        assertEquals(1, result.getSuccessCount());
        assertEquals(List.of(5), result.getFailedIds());
        verify(subscriptionService, times(1)).updateById(any());
    }

    // ---------- 辅助 ----------

    private PtSubscriptionPlus activeTv(int id, int total) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setTmdbId("1396");
        sub.setMediaType("TV");
        sub.setSeason(1);
        sub.setTotalEpisodes(total);
        sub.setStatus("ACTIVE");
        return sub;
    }

    private PtSubscriptionEpisodePlus episode(int number, String state) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setId(number * 100);
        ep.setEpisode(number);
        ep.setState(state);
        return ep;
    }
}
