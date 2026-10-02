package com.osr.openliststrm.pt.search;

import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.filter.RejectCode;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import com.osr.openliststrm.pt.subscription.dto.SearchCandidateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceSearchServiceTest {

    private SearchSupplementService supplement;
    private SubscriptionEngine engine;
    private TorrentFilterEngine filterEngine;
    private IPtFilterConfigPlusService filterConfigService;
    private IPtDownloaderPlusService downloaderService;
    private DownloaderClientFactory clientFactory;
    private IDownloaderClient client;
    private ResourceSearchService service;

    @BeforeEach
    void setUp() {
        supplement = mock(SearchSupplementService.class);
        engine = mock(SubscriptionEngine.class);
        filterEngine = mock(TorrentFilterEngine.class);
        filterConfigService = mock(IPtFilterConfigPlusService.class);
        IPtTorrentBlacklistPlusService blacklistService = mock(IPtTorrentBlacklistPlusService.class);
        downloaderService = mock(IPtDownloaderPlusService.class);
        clientFactory = mock(DownloaderClientFactory.class);
        client = mock(IDownloaderClient.class);
        when(clientFactory.get(any())).thenReturn(client);
        when(filterConfigService.getConfig()).thenReturn(new PtFilterConfigPlus());
        when(blacklistService.list()).thenReturn(List.of());
        // DTO 转换是 SearchSupplementService 的真实逻辑，这里只按标题与做种数还原，够断言用
        when(supplement.toCandidateDtos(anyList())).thenAnswer(inv -> inv.<List<TorrentInfo>>getArgument(0).stream()
                .map(t -> SearchCandidateDTO.builder().title(t.getTitle()).seeders(t.getSeeders()).build())
                .toList());
        service = new ResourceSearchService(supplement, engine, filterEngine, filterConfigService,
                blacklistService, downloaderService, clientFactory);
    }

    private static TorrentInfo torrent(String title, int seeders) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setSeeders(seeders);
        t.setDownloadUrl("http://site/dl/" + title);
        return t;
    }

    // ---------------- 搜索 ----------------

    @Test
    void 关键词太短直接拒绝_不发请求() {
        assertThrows(IllegalArgumentException.class, () -> service.search(" 三 ", null));
        verify(supplement, never()).searchKeyword(anyString(), any());
    }

    @Test
    void 没有启用的索引器时直说_不返回空结果() {
        when(supplement.hasNoEnabledIndexer()).thenReturn(true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.search("三体", null));
        assertTrue(e.getMessage().contains("没有启用中的索引器"));
    }

    /** 规则只标注不淘汰：被规则挡的那条照样在结果里，只是带上原因 */
    @Test
    void 全局规则只标注不淘汰_按做种数降序() {
        TorrentInfo low = torrent("三体 720p", 3);
        TorrentInfo high = torrent("三体 2160p", 50);
        when(supplement.searchKeyword(eq("三体"), any())).thenReturn(List.of(low, high));
        when(filterEngine.evaluate(anyList(), any(), any(), any())).thenReturn(List.of(
                TorrentFilterEngine.Verdict.reject(low, RejectCode.RESOLUTION_NOT_ALLOWED, "分辨率 720p 不在白名单 [2160p]"),
                TorrentFilterEngine.Verdict.accept(high)));

        ResourceSearchService.Result result = service.search(" 三体 ", List.of(1));

        assertEquals(2, result.candidateCount());
        assertEquals(1, result.rejectedCount());
        assertEquals("三体 2160p", result.items().get(0).getTitle(), "做种多的排前面");
        assertNull(result.items().get(0).getRuleRejection());
        assertEquals("分辨率不在白名单", result.items().get(1).getRuleRejection());
        assertEquals("分辨率 720p 不在白名单 [2160p]", result.items().get(1).getRuleRejectionDetail());
        verify(engine).markHitAndRun(anyList());
        verify(supplement).searchKeyword("三体", List.of(1));
    }

    @Test
    void 规则判定出错时照样返回结果_只是不带标注() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(torrent("三体", 1)));
        when(filterEngine.evaluate(anyList(), any(), any(), any())).thenThrow(new IllegalStateException("坏配置"));

        ResourceSearchService.Result result = service.search("三体", null);

        assertEquals(1, result.candidateCount());
        assertEquals(0, result.rejectedCount());
    }

    // ---------------- 直接下载 ----------------

    private static PtDownloaderPlus downloader(String enabled, String role) {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(7);
        d.setName("qb");
        d.setEnabled(enabled);
        d.setRole(role);
        d.setSavePath("/downloads/");
        d.setSmartClassifyLevel("CATEGORY");
        return d;
    }

    private static ResourcePushRequest pushRequest(String title) {
        ResourcePushRequest r = new ResourcePushRequest();
        r.setDownloaderId(7);
        r.setTitle(title);
        r.setDownloadUrl("http://site/dl/1");
        return r;
    }

    @Test
    void 直接下载_打独立标签_不暂停_按智能分类落盘() throws Exception {
        when(downloaderService.getById(7)).thenReturn(downloader("1", "DOWNLOAD"));

        String savePath = service.push(pushRequest("Dune.Part.Two.2024.2160p"));

        // fillParsed 被 mock 掉，解析不出季集号，按电影落盘
        assertEquals("/downloads/电影", savePath);
        verify(client).addTorrent(any(), eq("http://site/dl/1"), eq("/downloads/电影"),
                eq(ResourceSearchService.MANUAL_TAG), eq(false));
    }

    @Test
    void 直接下载_剧集落进剧集目录() throws Exception {
        when(downloaderService.getById(7)).thenReturn(downloader("1", null));
        doAnswerParsedSeason();

        assertEquals("/downloads/剧集", service.push(pushRequest("Three.Body.S01E01")));
    }

    private void doAnswerParsedSeason() {
        org.mockito.Mockito.doAnswer(inv -> {
            inv.<TorrentInfo>getArgument(0).setParsedSeason(1);
            return null;
        }).when(engine).fillParsed(any());
    }

    @Test
    void 直接下载_只做种的下载器拒绝() throws Exception {
        when(downloaderService.getById(7)).thenReturn(downloader("1", "SEED_ONLY"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.push(pushRequest("x")));
        assertTrue(e.getMessage().contains("只用于做种"));
        verify(client, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 直接下载_停用的下载器拒绝() {
        when(downloaderService.getById(7)).thenReturn(downloader("0", "DOWNLOAD"));

        assertThrows(IllegalArgumentException.class, () -> service.push(pushRequest("x")));
    }

    @Test
    void 直接下载_没有下载链接拒绝() {
        ResourcePushRequest r = pushRequest("x");
        r.setDownloadUrl(" ");

        assertThrows(IllegalArgumentException.class, () -> service.push(r));
    }

    @Test
    void 直接下载_下载器报错时把原因带回给用户() throws Exception {
        when(downloaderService.getById(7)).thenReturn(downloader("1", "DOWNLOAD"));
        doThrow(new IOException("Fails.")).when(client).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.push(pushRequest("x")));
        assertTrue(e.getMessage().contains("Fails."), e.getMessage());
    }
}
