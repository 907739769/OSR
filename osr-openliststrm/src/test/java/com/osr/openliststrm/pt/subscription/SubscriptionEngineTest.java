package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.dto.MatchResult;
import com.osr.openliststrm.pt.ws.PtStatusWebSocket;
import com.osr.openliststrm.rename.MediaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
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
    @Mock private IPtTorrentBlacklistPlusService blacklistService;
    @Mock private TmdbSearchService tmdbSearchService;
    @Mock private IPtIndexerPlusService indexerService;

    private SubscriptionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SubscriptionEngine(
                subscriptionService, episodeService, recordService, downloaderService,
                filterConfigService, downloaderClientFactory,
                new TorrentFilterEngine(), new SubscriptionMatcher(), searchLogService,
                blacklistService, tmdbSearchService, indexerService);
        when(blacklistService.list()).thenReturn(new ArrayList<>());

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
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());

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
        verify(downloaderClient).addTorrent(any(), anyString(), savePath.capture(), tag.capture(), anyBoolean());
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
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 目标集已在途_跳过不重复推送() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "IN_FLIGHT")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
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
        existing.setGuidHash(com.osr.openliststrm.pt.indexer.GuidHasher.hash("g1"));
        existing.setDownloaderId(1);
        existing.setIndexerId(1);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(existing));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    /** 构造一条落在同一 (indexer_id, guid_hash) 上的历史失败记录 */
    private PtDownloadRecordPlus failedRecord(int id, String guid, String failReasonCode) {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(id);
        r.setGuidHash(com.osr.openliststrm.pt.indexer.GuidHasher.hash(guid));
        r.setIndexerId(1);
        r.setDownloaderId(1);
        r.setState("FAILED");
        r.setFailReasonCode(failReasonCode);
        return r;
    }

    @Test
    void 可重试的失败记录_不排除该候选_复用原行重新推送() throws Exception {
        // TORRENT_NOT_FOUND 说的是"下载器那边出了状况"，种子本身可能完好。旧实现不看 state，
        // 这条记录会把该种子对该索引器永久封死，该集只有这一个资源时就再也补不回来了。
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(102, 2, "MISSING")));
        PtDownloadRecordPlus failed = failedRecord(777, "g1", "TORRENT_NOT_FOUND");
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(failed));
        when(recordService.getOne(any(Wrapper.class), eq(false))).thenReturn(failed);
        when(recordService.update(any(), any(Wrapper.class))).thenReturn(true);

        assertEquals(1, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));

        // 复用原行而不是插新行：uk_indexer_guid 是唯一索引，插新行必撞约束
        verify(recordService, never()).save(any());
        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("PUSHED", captor.getValue().getState());
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 复用失败记录后_集关联到原记录id() throws Exception {
        // record.setId 若漏了，pt_subscription_episode.download_id 会写成 null，
        // 该集之后既追踪不到下载进度，失败时也回退不回 MISSING
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(102, 2, "MISSING")));
        PtDownloadRecordPlus failed = failedRecord(777, "g1", "TORRENT_NOT_FOUND");
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(failed));
        when(recordService.getOne(any(Wrapper.class), eq(false))).thenReturn(failed);
        when(recordService.update(any(), any(Wrapper.class))).thenReturn(true);

        engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p")));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).updateBatchById(captor.capture());
        PtSubscriptionEpisodePlus claimed = (PtSubscriptionEpisodePlus) captor.getValue().get(0);
        assertEquals(777, claimed.getDownloadId());
    }

    @Test
    void 不可重试的失败记录_仍然排除该候选() throws Exception {
        // 僵尸超时基本等于死种，再选它就是重复踩同一个坑
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(102, 2, "MISSING")));
        when(recordService.list(any(Wrapper.class)))
                .thenReturn(List.of(failedRecord(777, "g1", "ZOMBIE_TIMEOUT")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 失败原因码为空的历史记录_按不可重试处理() throws Exception {
        // fail_reason_code 是 20260738 迁移才加的列，更早的失败记录该列为空。
        // 把它们当成可重试，会让一批陈年失败种子在升级后突然重新涌入候选池。
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(102, 2, "MISSING")));
        when(recordService.list(any(Wrapper.class)))
                .thenReturn(List.of(failedRecord(777, "g1", null)));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
    }

    @Test
    void 复用时被并发抢先_影响行数为0_回滚占位不推送() throws Exception {
        // 两个分组同时查到同一行可重试失败记录，条件更新只有一个能成功
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 2)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(102, 2, "MISSING")));
        PtDownloadRecordPlus failed = failedRecord(777, "g1", "TORRENT_NOT_FOUND");
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(failed));
        when(recordService.getOne(any(Wrapper.class), eq(false))).thenReturn(failed);
        when(recordService.update(any(), any(Wrapper.class))).thenReturn(false);

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
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
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
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
    void 季包标题带集数区间_只占位区间内的缺失集() throws Exception {
        // 用户实测场景：50 集的番分成上/中/下发布，先来的包标题写着 [01-03]，实际只含前 3 集。
        // 不解析这个区间就会判成整季包，把 5 集全占位成在途——包外的集下不到、不会退回缺失、
        // 补搜与 RSS 又只认 MISSING，等于永久卡死
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 5)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING"),
                episode(104, 4, "MISSING"), episode(105, 5, "MISSING")));

        int pushed = engine.process(List.of(
                torrent("Some.Show.S01.1080p.WEB-DL [01-03]", "g-part", 10, "1080p")));

        assertEquals(1, pushed);
        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        // 落库的是区间而不是季包哨兵值 -1
        assertEquals(1, captor.getValue().getEpisode());
        assertEquals(3, captor.getValue().getEpisodeEnd());
        // 只占位第 1-3 集，第 4、5 集仍是缺失，继续参与后续搜索
        verify(episodeService, times(3)).update(any(), any(Wrapper.class));
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
                .when(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());

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
        // 两个下载器初始负载相等，谁先经过"选下载器+占位自增"的临界区就tie-break选中d1，
        // 另一组感知到 d1 已被占用后转而选 d2；process() 用虚拟线程并行处理各分组，
        // 具体哪一组先进临界区不确定，因此不断言顺序，只断言两条记录分别落在 d1/d2 上
        // （负载均衡真正生效——两组不会挤到同一个下载器）。
        assertEquals(2, saved.size());
        assertEquals(java.util.Set.of(1, 2), saved.stream()
                .map(PtDownloadRecordPlus::getDownloaderId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    // ---------- 并发上限 ----------

    @Test
    void 下载器maxConcurrent为0_不做限制_正常推送() throws Exception {
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");
        downloader.setMaxConcurrent(0);
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader));
        List<PtDownloadRecordPlus> heavyLoad = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PtDownloadRecordPlus r = new PtDownloadRecordPlus();
            r.setDownloaderId(1);
            heavyLoad.add(r);
        }
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(heavyLoad);
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(1, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 下载器maxConcurrent为null_不做限制_正常推送() throws Exception {
        // setUp() 里的默认下载器未设置 maxConcurrent，Integer 包装类型默认为 null
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(1, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 下载器已达最大并发_跳过本轮_不占位不落库不推送() throws Exception {
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");
        downloader.setMaxConcurrent(2);
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader));
        PtDownloadRecordPlus active1 = new PtDownloadRecordPlus();
        active1.setDownloaderId(1);
        PtDownloadRecordPlus active2 = new PtDownloadRecordPlus();
        active2.setDownloaderId(1);
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(active1, active2));
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));

        verify(episodeService, never()).update(any(), any(Wrapper.class));
        verify(recordService, never()).save(any());
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
        verify(searchLogService).recordSummary(eq(10), eq(1), eq(SearchLogService.SOURCE_RSS), contains("并发"));
    }

    @Test
    void 下载器未达最大并发_正常推送() throws Exception {
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");
        downloader.setMaxConcurrent(2);
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader));
        PtDownloadRecordPlus active1 = new PtDownloadRecordPlus();
        active1.setDownloaderId(1);
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(active1));
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(1, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
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
        verify(downloaderClient, times(2)).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
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
    void resolveTargets_集数区间_只占位区间内的缺失集() throws Exception {
        // 种子标题 S01E02-04（区间 2~4），订阅缺 1、2、3、4 集：只有 2、3、4 该被占位，1 不动
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 4);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"),
                episode(103, 3, "MISSING"), episode(104, 4, "MISSING")));

        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");

        boolean pushed = engine.handleGroup(new MatchResult(sub, 2, 4),
                List.of(torrent("Some.Show.S01E02-04.1080p", "g-range", 10, "1080p")),
                filterConfigService.getConfig(),
                new LinkedHashMap<>(),
                List.of(downloader),
                new LinkedHashMap<>(),
                SearchLogService.SOURCE_RSS,
                TorrentBlacklist.EMPTY, PushMode.FILL_MISSING);

        assertTrue(pushed);
        // 只占位第 2、3、4 集，第 1 集不动
        verify(episodeService, org.mockito.Mockito.times(3)).update(any(), any(Wrapper.class));

        // 下载记录要落库区间结尾集号，否则列表页只能显示成单集
        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(2, captor.getValue().getEpisode());
        assertEquals(4, captor.getValue().getEpisodeEnd());
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
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
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
                .when(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        verify(searchLogService).recordSummary(eq(10), eq(1), eq(SearchLogService.SOURCE_RSS), anyString());
    }

    // ---------- WebSocket 状态推送 ----------

    @Test
    void 推送成功后_推送WebSocket订阅事件() throws Exception {
        // 不走 process()：process() 内部用虚拟线程并行处理各分组，而 Mockito 的
        // MockedStatic 只在注册它的线程上生效，跨线程调用会被判定为"零交互"。
        // handleGroup 才是真正触发 WebSocket 推送的地方，直接调用它既测到了目标逻辑，
        // 又避免了与并发模型打架。
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            boolean pushed = engine.handleGroup(new MatchResult(sub, 1),
                    List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")),
                    filterConfigService.getConfig(),
                    new LinkedHashMap<>(),
                    List.of(downloader),
                    new LinkedHashMap<>(),
                    SearchLogService.SOURCE_RSS,
                    TorrentBlacklist.EMPTY, PushMode.FILL_MISSING);

            assertTrue(pushed);
            ws.verify(() -> PtStatusWebSocket.pushSubscriptionEvent(same(sub)));
        }
    }

    @Test
    void pushBest成功_也推送WebSocket订阅事件() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 3);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            boolean pushed = engine.pushBest(sub, 2, List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p")));

            assertTrue(pushed);
            ws.verify(() -> PtStatusWebSocket.pushSubscriptionEvent(same(sub)));
        }
    }

    @Test
    void 推送失败回滚_不推送WebSocket订阅事件() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        when(recordService.save(any())).thenAnswer(inv -> {
            ((PtDownloadRecordPlus) inv.getArgument(0)).setId(999);
            return true;
        });
        org.mockito.Mockito.doThrow(new IOException("qb down"))
                .when(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

            ws.verify(() -> PtStatusWebSocket.pushSubscriptionEvent(any()), never());
        }
    }

    // ---------- 黑名单 ----------

    @Test
    void 黑名单命中GUID_淘汰不推送() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        PtTorrentBlacklistPlus rule = new PtTorrentBlacklistPlus();
        rule.setType(PtTorrentBlacklistPlus.TYPE_GUID);
        rule.setValue(com.osr.openliststrm.pt.indexer.GuidHasher.hash("g1"));
        when(blacklistService.list()).thenReturn(List.of(rule));

        int pushed = engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        assertEquals(0, pushed);
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
        ArgumentCaptor<List> verdicts = ArgumentCaptor.forClass(List.class);
        verify(searchLogService).recordVerdicts(eq(10), eq(1), eq(SearchLogService.SOURCE_RSS), verdicts.capture());
        TorrentFilterEngine.Verdict verdict = (TorrentFilterEngine.Verdict) verdicts.getValue().get(0);
        assertFalse(verdict.accepted());
        assertTrue(verdict.rejectReason().contains("拉黑"));
    }

    @Test
    void pushBest路径_黑名单命中发布组_淘汰不推送() {
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        PtTorrentBlacklistPlus rule = new PtTorrentBlacklistPlus();
        rule.setType(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP);
        rule.setValue("CHDWEB");
        when(blacklistService.list()).thenReturn(List.of(rule));
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);

        // 种子标题不带扩展名，就是 PT 站上的原样。此前这里补过一个占位的 ".mkv"，理由写的是
        // "MediaParser.extractBase 会把最后一段当扩展名剥离"——但那是 parse() 的行为，
        // fillParsed 走的 parseLocal() 传的是 stripExtension=false，本来就不剥扩展名。
        // 补上 ".mkv" 反而让标题以 " mkv" 结尾，SourceAndGroupExtractor 的 GROUP_END
        // （要求结尾是 "-xxx"）匹配不到发布组，parsedReleaseGroup 恒为 null，
        // 发布组黑名单永远命不中，本用例因此长期失败。
        TorrentInfo candidate = torrent("Show.Name.S01E01.1080p.WEB-DL.H264-CHDWEB", "g1", 10, "1080p");
        boolean pushed = engine.pushBest(sub, 1, List.of(candidate));

        assertFalse(pushed);
        verify(searchLogService).recordVerdicts(eq(10), eq(1), eq(SearchLogService.SOURCE_SUPPLEMENT), any(List.class));
    }

    // ---------- 洗版推送模式 ----------

    @Test
    void 洗版_占位从入库转为洗版中() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "IN_LIBRARY")));

        boolean pushed = engine.pushUpgrade(sub, 1,
                List.of(torrent("Some.Show.S01E01.2160p", "g-up", 10, "2160p")));

        assertTrue(pushed);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).updateBatchById(captor.capture());
        PtSubscriptionEpisodePlus claimed = (PtSubscriptionEpisodePlus) captor.getValue().get(0);
        assertEquals("UPGRADING", claimed.getState());
    }

    @Test
    void 洗版_目标集不在入库状态_不推送() throws Exception {
        // 已经在洗（UPGRADING）或已退回缺失的集都不该被再占一次
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "UPGRADING")));

        assertFalse(engine.pushUpgrade(sub, 1,
                List.of(torrent("Some.Show.S01E01.2160p", "g-up", 10, "2160p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 洗版_缺失集不会被洗版占走() throws Exception {
        // 缺集要走补缺集链路，洗版只认 IN_LIBRARY
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertFalse(engine.pushUpgrade(sub, 1,
                List.of(torrent("Some.Show.S01E01.2160p", "g-up", 10, "2160p"))));
    }

    @Test
    void 洗版_推送失败回滚到入库而不是缺失() throws Exception {
        // 退成 MISSING 会让这一集显示成缺失并被 RSS 从头重下一遍，比不洗版还糟
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "IN_LIBRARY")));
        doThrow(new IOException("下载器挂了")).when(downloaderClient)
                .addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());

        assertFalse(engine.pushUpgrade(sub, 1,
                List.of(torrent("Some.Show.S01E01.2160p", "g-up", 10, "2160p"))));

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor =
                ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        assertTrue(captor.getAllValues().stream().anyMatch(e -> "IN_LIBRARY".equals(e.getState())),
                "洗版推送失败必须把集退回 IN_LIBRARY");
        assertFalse(captor.getAllValues().stream().anyMatch(e -> "MISSING".equals(e.getState())),
                "洗版推送失败绝不能把集退成 MISSING");
    }

    @Test
    void 洗版_区间包不做展开_只动目标那一集() throws Exception {
        // 区间内其它集的质量基线没被比较过，连带替换很可能把它们换成更差的版本
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 4);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "IN_LIBRARY"), episode(102, 2, "IN_LIBRARY"),
                episode(103, 3, "IN_LIBRARY"), episode(104, 4, "IN_LIBRARY")));

        boolean pushed = engine.pushUpgrade(sub, 2,
                List.of(torrent("Some.Show.S01E02-04.2160p", "g-range", 10, "2160p")));

        assertTrue(pushed);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(episodeService).updateBatchById(captor.capture());
        assertEquals(1, captor.getValue().size(), "洗版只该占位目标那一集");
    }

    @Test
    void 洗版_下载记录落的集号是目标集且不带区间() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 4);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(102, 2, "IN_LIBRARY")));

        engine.pushUpgrade(sub, 2, List.of(torrent("Some.Show.S01E02-04.2160p", "g-range", 10, "2160p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(2, captor.getValue().getEpisode());
        assertEquals(null, captor.getValue().getEpisodeEnd());
    }

    // ---------- H&R 站点标记 ----------

    private PtIndexerPlus hrIndexer(int id) {
        PtIndexerPlus i = new PtIndexerPlus();
        i.setId(id);
        i.setHrEnabled("1");
        i.setHrSeedHours(72);
        return i;
    }

    @Test
    void 开启规避HR_来自HR站点的候选被淘汰不推送() throws Exception {
        PtFilterConfigPlus config = new PtFilterConfigPlus();
        config.setMinSeeders(0);
        config.setMinSize(0L);
        config.setMaxSize(0L);
        config.setFreeOnly("0");
        config.setSortPriority("SEEDERS");
        config.setPreferredSize(0L);
        config.setAvoidHitAndRun("1");
        when(filterConfigService.getConfig()).thenReturn(config);
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        when(indexerService.list()).thenReturn(List.of(hrIndexer(1)));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 索引器未开HR考核_候选不被标记为HR() {
        // 判不出来就按"不考核"处理，宁可少规避一次，也不能凭空把正常候选当成 H&R 淘汰
        PtFilterConfigPlus config = new PtFilterConfigPlus();
        config.setMinSeeders(0);
        config.setMinSize(0L);
        config.setMaxSize(0L);
        config.setFreeOnly("0");
        config.setSortPriority("SEEDERS");
        config.setPreferredSize(0L);
        config.setAvoidHitAndRun("1");
        when(filterConfigService.getConfig()).thenReturn(config);
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        PtIndexerPlus noHr = hrIndexer(1);
        noHr.setHrEnabled("0");
        when(indexerService.list()).thenReturn(List.of(noHr));

        assertEquals(1, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
    }

    // ---------- fillParsed 承接质量标签 ----------

    @Test
    void fillParsed_把解析出的质量标签填进种子() {
        // 这些标签一直被 SourceAndGroupExtractor 解析着，只是此前没有字段承接、在 fillParsed 就被丢掉，
        // 过滤引擎因此拿不到任何画质信息。本用例走真实的 MediaParser，锁死整条链路而不是只测 setter。
        TorrentInfo t = new TorrentInfo();
        t.setTitle("Show.Name.S01E01.2160p.WEB-DL.HDR10.Atmos.10bit-CHDWEB");

        engine.fillParsed(t);

        assertTrue(t.getParsedTags().contains("HDR10"));
        assertTrue(t.getParsedTags().contains("10BIT"));
        assertEquals("WEBDL", t.getParsedSource());
        assertEquals("CHDWEB", t.getParsedReleaseGroup());
    }

    @Test
    void fillParsed_音视频编码也并进标签_否则Atmos这类配置永远匹配不上() {
        // CodecExtractor 跑在 SourceAndGroupExtractor 之前，会先把 "Atmos" 匹进 audioCodec
        // 并从标题里抹掉，SourceAndGroupExtractor 的 TAGS 正则再扫时已经找不到它。
        // 只读 MediaInfo.tags 的话，「必须带 Atmos」会一条候选都匹配不上。
        TorrentInfo t = new TorrentInfo();
        t.setTitle("Show.Name.S01E01.2160p.WEB-DL.HDR10.Atmos.H265-CHDWEB");

        engine.fillParsed(t);

        assertTrue(t.getParsedTags().stream().anyMatch("ATMOS"::equalsIgnoreCase));
        assertTrue(t.getParsedTags().stream().anyMatch("H265"::equalsIgnoreCase));
        assertTrue(t.getParsedTags().contains("HDR10"));
    }

    @Test
    void fillParsed_标签按大写去重_同一标签不重复出现() {
        TorrentInfo t = new TorrentInfo();
        t.setTitle("Show.Name.S01E01.1080p.WEB-DL.Atmos-CHDWEB");

        engine.fillParsed(t);

        long atmosCount = t.getParsedTags().stream().filter("ATMOS"::equalsIgnoreCase).count();
        assertEquals(1, atmosCount);
    }

    @Test
    void fillParsed_没有质量标签的标题_得到空列表而非null() {
        // 引擎侧会直接遍历 parsedTags，null 会在过滤时炸出 NPE
        TorrentInfo t = new TorrentInfo();
        t.setTitle("Show.Name.S01E01.1080p");

        engine.fillParsed(t);

        assertTrue(t.getParsedTags().isEmpty());
    }
}
