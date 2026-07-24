package com.ruoyi.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.downloader.DownloaderClientFactory;
import com.ruoyi.openliststrm.pt.downloader.IDownloaderClient;
import com.ruoyi.openliststrm.pt.filter.TorrentFilterEngine;
import com.ruoyi.openliststrm.pt.model.TorrentInfo;
import com.ruoyi.openliststrm.rename.MediaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionEngineTest {

    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private IPtDownloadRecordPlusService recordService;
    @Mock private IPtDownloaderPlusService downloaderService;
    @Mock private IPtFilterConfigPlusService filterConfigService;
    @Mock private DownloaderClientFactory downloaderClientFactory;
    @Mock private IDownloaderClient downloaderClient;
    @Mock private SearchLogService searchLogService;

    private SubscriptionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SubscriptionEngine(
                subscriptionService, episodeService, recordService, downloaderService,
                filterConfigService, downloaderClientFactory,
                new TorrentFilterEngine(), new SubscriptionMatcher(), searchLogService);

        PtFilterConfigPlus config = new PtFilterConfigPlus();
        config.setMinSeeders(0);
        config.setMinSize(0L);
        config.setMaxSize(0L);
        config.setFreeOnly("0");
        config.setResolutionPriority("2160p,1080p,720p");
        config.setSortPriority("RESOLUTION,SEEDERS");
        config.setPreferredSize(0L);
        when(filterConfigService.getConfig()).thenReturn(config);

        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);

        when(recordService.list(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(recordService.save(any())).thenReturn(true);
        // 默认占位成功
        when(episodeService.update(any(), any(Wrapper.class))).thenReturn(true);
    }

    private PtSubscriptionPlus tvSub(int id, String title, int season, int total) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setMediaType("TV");
        sub.setTitle(title);
        sub.setSeason(season);
        sub.setTotalEpisodes(total);
        sub.setStatus("ACTIVE");
        return sub;
    }

    private PtSubscriptionEpisodePlus episode(int id, int number, String state) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setId(id);
        ep.setEpisode(number);
        ep.setState(state);
        return ep;
    }

    private TorrentInfo torrent(String title, String guid, int seeders, String resolution) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setGuid(guid);
        t.setSeeders(seeders);
        t.setSize(5_000_000_000L);
        t.setIndexerId(1);
        t.setDownloadUrl("http://indexer/download?id=" + guid);
        return t;
    }

    // ---------- 基本推送 ----------

    @Test
    void 命中缺失集_推送并落记录() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        int pushed = engine.process(List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1", 10, "1080p")));

        assertEquals(1, pushed);
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString());

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        PtDownloadRecordPlus record = captor.getValue();
        assertEquals(10, record.getSubId());
        assertEquals(2, record.getEpisode());
        assertEquals("g1", record.getGuid());
        assertEquals("PUSHED", record.getState());
        assertEquals(64, record.getGuidHash().length());
        assertTrue(record.getTrackingTag().startsWith("osr-pt-"));
        assertEquals("osr-pt-" + record.getGuidHash().substring(0, 16), record.getTrackingTag());
    }

    @Test
    void 推送时带上下载器的保存路径与标签() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        ArgumentCaptor<String> savePath = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tag = ArgumentCaptor.forClass(String.class);
        verify(downloaderClient).addTorrent(any(), anyString(), savePath.capture(), tag.capture());
        assertEquals("/data/downloads", savePath.getValue());
        // 公共标签 + 唯一标签，用逗号分隔一次打上
        assertTrue(tag.getValue().contains("osr-pt"));
    }

    // ---------- 跳过 ----------

    @Test
    void 无活跃订阅_不做任何事() {
        when(subscriptionService.listActive()).thenReturn(List.of());

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(episodeService, never()).listBySubscription(any());
    }

    @Test
    void 匹配不到订阅_跳过() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Other Show", 1, 3)));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString());
    }

    @Test
    void 目标集已在途_跳过不重复推送() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "IN_FLIGHT")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString());
    }

    @Test
    void 目标集已入库_跳过() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "IN_LIBRARY")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
    }

    @Test
    void 该guid已有下载记录_剔除不重复推送() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(102, 2, "MISSING")));
        PtDownloadRecordPlus existing = new PtDownloadRecordPlus();
        existing.setGuidHash(com.ruoyi.openliststrm.pt.indexer.GuidHasher.hash("g1"));
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(existing));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString());
    }

    @Test
    void 全部候选被过滤规则淘汰_跳过() throws Exception {
        PtFilterConfigPlus strict = new PtFilterConfigPlus();
        strict.setMinSeeders(100);
        strict.setMinSize(0L);
        strict.setMaxSize(0L);
        strict.setFreeOnly("0");
        strict.setResolutionPriority("1080p");
        strict.setSortPriority("SEEDERS");
        strict.setPreferredSize(0L);
        when(filterConfigService.getConfig()).thenReturn(strict);
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 3, "1080p"))));
    }

    @Test
    void CAS占位失败_跳过不推送() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        // 模拟并发轮询已抢走该集
        when(episodeService.update(any(), any(Wrapper.class))).thenReturn(false);

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString());
        verify(recordService, never()).save(any());
    }

    // ---------- 择优 ----------

    @Test
    void 同一集多个候选_只推最优的一个() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        int pushed = engine.process(List.of(
                torrent("Some.Show.S01E01.720p.WEB-DL", "g-720", 10, "720p"),
                torrent("Some.Show.S01E01.2160p.WEB-DL", "g-4k", 10, "2160p"),
                torrent("Some.Show.S01E01.1080p.WEB-DL", "g-1080", 10, "1080p")));

        assertEquals(1, pushed);
        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        // 默认排序维度是 RESOLUTION 优先，优先级列表 2160p 在最前
        assertEquals("g-4k", captor.getValue().getGuid());
    }

    // ---------- 季包 ----------

    @Test
    void 季包_一条记录集号为负一_所有缺失集共同指向它() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 4)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "IN_LIBRARY"), episode(102, 2, "MISSING"),
                episode(103, 3, "MISSING"), episode(104, 4, "IN_FLIGHT")));

        int pushed = engine.process(List.of(torrent("Some.Show.S01.1080p.WEB-DL.COMPLETE", "g-pack", 10, "1080p")));

        assertEquals(1, pushed);
        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(-1, captor.getValue().getEpisode());
        // 只占位 MISSING 的第 2、3 集，已入库和在途的不动
        verify(episodeService, times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    void 季包_无缺失集_跳过() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "IN_LIBRARY"), episode(102, 2, "IN_LIBRARY")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01.1080p.COMPLETE", "g-pack", 10, "1080p"))));
    }

    // ---------- 推送失败回滚 ----------

    @Test
    void 推送失败_删记录并把集改回缺失() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        PtDownloadRecordPlus saved = new PtDownloadRecordPlus();
        when(recordService.save(any())).thenAnswer(inv -> {
            ((PtDownloadRecordPlus) inv.getArgument(0)).setId(999);
            return true;
        });
        org.mockito.Mockito.doThrow(new IOException("qb down"))
                .when(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString());

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));

        verify(recordService).removeById(999);
        // 回滚：把占位过的集改回 MISSING（第二次 update 调用）
        verify(episodeService, times(2)).update(any(), any(Wrapper.class));
    }

    // ---------- 下载器 ----------

    @Test
    void 无启用的下载器_不推送并返回0() throws Exception {
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of());
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(recordService, never()).save(any());
    }

    @Test
    void 两个启用下载器_未指定_选负载最小的推送() throws Exception {
        PtDownloaderPlus d1 = new PtDownloaderPlus();
        d1.setId(1); d1.setType("QBITTORRENT"); d1.setSavePath("/data/downloads"); d1.setTag("osr-pt"); d1.setEnabled("1");
        PtDownloaderPlus d2 = new PtDownloaderPlus();
        d2.setId(2); d2.setType("QBITTORRENT"); d2.setSavePath("/data/downloads2"); d2.setTag("osr-pt2"); d2.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d1, d2));
        // 区分两类 recordService.list 查询：guid_hash 查询（excludeAlreadyRecorded）与
        // downloader_id/state 查询（loadDownloaderLoadCounts）用同一个方法签名，按 SQL 片段里的目标字段区分桩数据
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("guid_hash"))))
                .thenReturn(List.of());
        PtDownloadRecordPlus loadForD1a = new PtDownloadRecordPlus();
        loadForD1a.setDownloaderId(1);
        PtDownloadRecordPlus loadForD1b = new PtDownloadRecordPlus();
        loadForD1b.setDownloaderId(1);
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(loadForD1a, loadForD1b)); // d1 在途记录数=2，d2=0
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(2, captor.getValue().getDownloaderId());
    }

    @Test
    void 订阅指定下载器_即使负载更高_仍选择指定的() throws Exception {
        PtDownloaderPlus d1 = new PtDownloaderPlus();
        d1.setId(1); d1.setType("QBITTORRENT"); d1.setSavePath("/data/downloads"); d1.setTag("osr-pt"); d1.setEnabled("1");
        PtDownloaderPlus d2 = new PtDownloaderPlus();
        d2.setId(2); d2.setType("QBITTORRENT"); d2.setSavePath("/data/downloads2"); d2.setTag("osr-pt2"); d2.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d1, d2));
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("guid_hash"))))
                .thenReturn(List.of());
        PtDownloadRecordPlus loadForD1a = new PtDownloadRecordPlus();
        loadForD1a.setDownloaderId(1);
        PtDownloadRecordPlus loadForD1b = new PtDownloadRecordPlus();
        loadForD1b.setDownloaderId(1);
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(loadForD1a, loadForD1b)); // d1 负载更高：2 对 0
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        sub.setDownloaderId(1);
        when(subscriptionService.listActive()).thenReturn(List.of(sub));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(1, captor.getValue().getDownloaderId());
    }

    @Test
    void 指定下载器已禁用_回退到负载最低的启用下载器() throws Exception {
        PtDownloaderPlus d1 = new PtDownloaderPlus();
        d1.setId(1); d1.setType("QBITTORRENT"); d1.setSavePath("/data/downloads"); d1.setTag("osr-pt"); d1.setEnabled("1");
        PtDownloaderPlus d2 = new PtDownloaderPlus();
        d2.setId(2); d2.setType("QBITTORRENT"); d2.setSavePath("/data/downloads2"); d2.setTag("osr-pt2"); d2.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d1, d2));
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("guid_hash"))))
                .thenReturn(List.of());
        PtDownloadRecordPlus loadForD1 = new PtDownloadRecordPlus();
        loadForD1.setDownloaderId(1);
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(loadForD1)); // d1 负载=1，d2 负载=0
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        sub.setDownloaderId(99); // 不在启用列表里
        when(subscriptionService.listActive()).thenReturn(List.of(sub));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(2, captor.getValue().getDownloaderId());
    }

    @Test
    void 同一批次连续命中_第二次感知前一次推送的负载增量() throws Exception {
        PtDownloaderPlus d1 = new PtDownloaderPlus();
        d1.setId(1); d1.setType("QBITTORRENT"); d1.setSavePath("/data/downloads"); d1.setTag("osr-pt"); d1.setEnabled("1");
        PtDownloaderPlus d2 = new PtDownloaderPlus();
        d2.setId(2); d2.setType("QBITTORRENT"); d2.setSavePath("/data/downloads2"); d2.setTag("osr-pt2"); d2.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d1, d2));
        // 两个下载器初始负载都是 0（沿用 setUp() 的默认空列表桩）
        when(subscriptionService.listActive()).thenReturn(List.of(
                tvSub(10, "Show A", 1, 1), tvSub(20, "Show B", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        when(episodeService.listBySubscription(20)).thenReturn(List.of(episode(201, 1, "MISSING")));

        engine.process(List.of(
                torrent("Show.A.S01E01.1080p", "gA", 10, "1080p"),
                torrent("Show.B.S01E01.1080p", "gB", 10, "1080p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, times(2)).save(captor.capture());
        List<PtDownloadRecordPlus> saved = captor.getAllValues();
        // 两个下载器初始负载相等，按顺序 tie-break 选第一个(d1)；
        // 第二次推送前 d1 已被 +1，第二组应该感知到这个变化转而选 d2
        assertEquals(1, saved.get(0).getDownloaderId());
        assertEquals(2, saved.get(1).getDownloaderId());
    }

    // ---------- 多订阅 ----------

    @Test
    void 多个订阅各命中一集_各推一个() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(
                tvSub(10, "Show A", 1, 1), tvSub(20, "Show B", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        when(episodeService.listBySubscription(20)).thenReturn(List.of(episode(201, 1, "MISSING")));

        int pushed = engine.process(List.of(
                torrent("Show.A.S01E01.1080p", "gA", 10, "1080p"),
                torrent("Show.B.S01E01.1080p", "gB", 10, "1080p")));

        assertEquals(2, pushed);
        verify(downloaderClient, times(2)).addTorrent(any(), anyString(), anyString(), anyString());
    }

    @Test
    void 空种子列表_返回0() {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));

        assertEquals(0, engine.process(List.of()));
    }

    // ---------- pushBest（搜索补集复用） ----------

    @Test
    void pushBest_指定单集_只占位该集() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 3);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        boolean pushed = engine.pushBest(sub, 2, List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p")));

        assertTrue(pushed);
        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(2, captor.getValue().getEpisode());
    }

    @Test
    void pushBest_季包目标_占位全部缺失集() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 4);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "IN_LIBRARY"), episode(102, 2, "MISSING"),
                episode(103, 3, "MISSING"), episode(104, 4, "IN_FLIGHT")));

        boolean pushed = engine.pushBest(sub, SubscriptionMatcher.SEASON_PACK,
                List.of(torrent("Some.Show.S01.1080p.COMPLETE", "g-pack", 10, "1080p")));

        assertTrue(pushed);
        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(-1, captor.getValue().getEpisode());
        // 只占位第 2、3 集（IN_LIBRARY 和 IN_FLIGHT 的不动）
        verify(episodeService, times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    void pushBest_电影目标episode恒为0() throws Exception {
        PtSubscriptionPlus movie = new PtSubscriptionPlus();
        movie.setId(20);
        movie.setMediaType("MOVIE");
        movie.setTitle("Some Movie");
        movie.setSeason(0);
        movie.setTotalEpisodes(1);
        movie.setStatus("ACTIVE");
        when(episodeService.listBySubscription(20)).thenReturn(List.of(episode(201, 0, "MISSING")));

        boolean pushed = engine.pushBest(movie, 0, List.of(torrent("Some.Movie.2020.1080p", "g1", 10, "1080p")));

        assertTrue(pushed);
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString());
    }

    @Test
    void pushBest_候选为空_返回false() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertFalse(engine.pushBest(sub, 1, List.of()));
    }

    /**
     * 回归测试：候选为空时必须在查库前短路，不能把空列表传给 recordService.list()。
     * MyBatis-Plus 的 QueryWrapper.in("guid_hash", emptyList) 会生成 "IN ()"，
     * 在真实 MySQL 上是语法错误（Mock 环境不会暴露，只有真实数据库才会报错，
     * 这个用例是在浏览器端到端验证时发现的真实生产问题——见 SearchSupplementService
     * 在无索引器/无搜索结果时会以空列表调用 pushBest）。
     * 注意：本任务（任务7）给 pushBest 新增了批内下载器负载统计查询（downloader_id 维度，
     * ids 来自非空的启用下载器列表，不存在 IN () 风险），这是合法的新调用，
     * 因此断言收窄为「不按 guid_hash 查询」而不是「完全不查询」。
     */
    @Test
    void pushBest_候选为空_不查询已有下载记录() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.pushBest(sub, 1, List.of());

        verify(recordService, never()).list(argThat((Wrapper<PtDownloadRecordPlus> w) ->
                w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("guid_hash")));
    }

    // ---------- 匹配日志 ----------

    @Test
    void RSS路径_候选被淘汰_按RSS来源记录裁决() throws Exception {
        PtFilterConfigPlus strict = new PtFilterConfigPlus();
        strict.setMinSeeders(100);
        strict.setMinSize(0L);
        strict.setMaxSize(0L);
        strict.setFreeOnly("0");
        strict.setResolutionPriority("1080p");
        strict.setSortPriority("SEEDERS");
        strict.setPreferredSize(0L);
        when(filterConfigService.getConfig()).thenReturn(strict);
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 3, "1080p")));

        ArgumentCaptor<List> verdicts = ArgumentCaptor.forClass(List.class);
        verify(searchLogService).recordVerdicts(eq(10), eq(1), eq(SearchLogService.SOURCE_RSS), verdicts.capture());
        assertEquals(1, verdicts.getValue().size());
        TorrentFilterEngine.Verdict verdict = (TorrentFilterEngine.Verdict) verdicts.getValue().get(0);
        assertFalse(verdict.accepted());
        assertTrue(verdict.rejectReason().contains("做种数"));
    }

    @Test
    void pushBest路径_候选被淘汰_按SUPPLEMENT来源记录裁决() {
        PtFilterConfigPlus strict = new PtFilterConfigPlus();
        strict.setMinSeeders(100);
        strict.setMinSize(0L);
        strict.setMaxSize(0L);
        strict.setFreeOnly("0");
        strict.setResolutionPriority("1080p");
        strict.setSortPriority("SEEDERS");
        strict.setPreferredSize(0L);
        when(filterConfigService.getConfig()).thenReturn(strict);
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.pushBest(sub, 1, List.of(torrent("Some.Show.S01E01.1080p", "g1", 3, "1080p")));

        verify(searchLogService).recordVerdicts(eq(10), eq(1), eq(SearchLogService.SOURCE_SUPPLEMENT), any(List.class));
    }

    @Test
    void 无可占位缺失集_记录摘要日志() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "IN_FLIGHT")));

        engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p")));

        verify(searchLogService).recordSummary(eq(10), eq(2), eq(SearchLogService.SOURCE_RSS), anyString());
    }

    @Test
    void 无可用下载器_记录摘要日志() throws Exception {
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of());
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        verify(searchLogService).recordSummary(eq(10), eq(1), eq(SearchLogService.SOURCE_RSS), anyString());
    }

    @Test
    void 推送失败_记录摘要日志() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        when(recordService.save(any())).thenAnswer(inv -> {
            ((PtDownloadRecordPlus) inv.getArgument(0)).setId(999);
            return true;
        });
        org.mockito.Mockito.doThrow(new IOException("qb down"))
                .when(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString());

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        verify(searchLogService).recordSummary(eq(10), eq(1), eq(SearchLogService.SOURCE_RSS), anyString());
    }
}
