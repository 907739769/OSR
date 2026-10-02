package com.osr.openliststrm.pt.clean;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtCleanRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.service.IPtCleanRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.task.HitAndRunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TorrentCleanServiceTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Mock private IPtDownloaderPlusService downloaderService;
    @Mock private IPtCleanRulePlusService ruleService;
    @Mock private IPtDownloadRecordPlusService recordService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private DownloaderClientFactory clientFactory;
    @Mock private IDownloaderClient client;

    private TorrentCleanService service;

    @BeforeEach
    void setUp() {
        service = new TorrentCleanService(downloaderService, ruleService, recordService, episodeService, clientFactory);
        when(clientFactory.get(any())).thenReturn(client);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of());
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
    }

    // ---------- 基本判定 ----------

    @Test
    void 做种时长与体积都达标时删除() throws Exception {
        givenRules(rule(0, null, 72));
        givenTorrents(torrent("hash1", "/data/A", 10 * GB, 80 * 3600));

        CleanSummary summary = service.clean(downloader());

        assertEquals(1, summary.getDeletedGroups());
        verify(client).deleteTorrent(any(), eq("hash1"), eq(true));
    }

    @Test
    void 做种时长不够时不删() throws Exception {
        givenRules(rule(0, null, 72));
        givenTorrents(torrent("hash1", "/data/A", 10 * GB, 3600));

        assertEquals(0, service.clean(downloader()).getDeletedGroups());
        verify(client, never()).deleteTorrent(any(), anyString(), anyBoolean());
    }

    @Test
    void 体积落不进任何规则区间时不删() throws Exception {
        // 规则只覆盖 50GB 以上，10GB 的种子没有任何规则说该删它
        givenRules(rule(50, null, 1));
        givenTorrents(torrent("hash1", "/data/A", 10 * GB, 999 * 3600));

        List<CleanGroupDecision> decisions = service.evaluate(downloader());

        assertFalse(decisions.get(0).isDeletable());
        assertEquals(CleanSkipReason.NO_RULE_MATCHED, decisions.get(0).getSkipReason());
    }

    @Test
    void 没有任何启用规则时一个都不删() throws Exception {
        when(ruleService.listEnabledByDownloader(any())).thenReturn(List.of());
        givenTorrents(torrent("hash1", "/data/A", 10 * GB, 9999 * 3600));

        CleanSummary summary = service.clean(downloader());

        assertTrue(summary.isNoRules());
        assertEquals(0, summary.getDeletedGroups());
        verify(client, never()).deleteTorrent(any(), anyString(), anyBoolean());
    }

    @Test
    void 未下载完成的种子不删() throws Exception {
        givenRules(rule(0, null, 0));
        DownloaderTorrent downloading = torrent("hash1", "/data/A", 10 * GB, 0);
        downloading.setProgress(0.5);
        givenTorrents(downloading);

        assertEquals(CleanSkipReason.NOT_COMPLETED, service.evaluate(downloader()).get(0).getSkipReason());
    }

    @Test
    void 校验中的种子不删() throws Exception {
        givenRules(rule(0, null, 0));
        DownloaderTorrent checking = torrent("hash1", "/data/A", 10 * GB, 999 * 3600);
        checking.setRawState("checkingUP");
        givenTorrents(checking);

        assertEquals(CleanSkipReason.BUSY_STATE, service.evaluate(downloader()).get(0).getSkipReason());
    }

    // ---------- 分级规则 ----------

    @Test
    void 分级规则按顺序取第一条体积命中的() throws Exception {
        // 50GB 以上做满 24 小时就删，其余要做满 240 小时
        givenRules(rule(50, null, 24), rule(0, 50d, 240));
        DownloaderTorrent big = torrent("big", "/data/big", 60 * GB, 30 * 3600);
        DownloaderTorrent small = torrent("small", "/data/small", 10 * GB, 30 * 3600);
        givenTorrents(big, small);

        service.clean(downloader());

        verify(client).deleteTorrent(any(), eq("big"), anyBoolean());
        verify(client, never()).deleteTorrent(any(), eq("small"), anyBoolean());
    }

    // ---------- 辅种整组同删 ----------

    @Test
    void 辅种组内有一个未达标则整组保留() throws Exception {
        givenRules(rule(0, null, 72));
        // 同一份文件、两个站的种子：第二个才加进来没多久
        givenTorrents(torrent("hashA", "/data/Show", 10 * GB, 100 * 3600),
                torrent("hashB", "/data/Show", 10 * GB, 3600));

        List<CleanGroupDecision> decisions = service.evaluate(downloader());

        assertEquals(1, decisions.size(), "共用同一内容路径的种子必须归成一组");
        assertFalse(decisions.get(0).isDeletable());
        verify(client, never()).deleteTorrent(any(), anyString(), anyBoolean());
    }

    @Test
    void 辅种组整组达标时最后一个才连文件一起删() throws Exception {
        givenRules(rule(0, null, 72));
        givenTorrents(torrent("hashA", "/data/Show", 10 * GB, 100 * 3600),
                torrent("hashB", "/data/Show", 10 * GB, 200 * 3600));

        CleanSummary summary = service.clean(downloader());

        var order = inOrder(client);
        order.verify(client).deleteTorrent(any(), eq("hashA"), eq(false));
        order.verify(client).deleteTorrent(any(), eq("hashB"), eq(true));
        assertEquals(1, summary.getDeletedGroups());
        assertEquals(2, summary.getDeletedTorrents());
        // 释放的空间按组算，不能把辅种份数乘进去
        assertEquals(10 * GB, summary.getFreedBytes());
    }

    @Test
    void 组内删除失败时中止本组且文件未删() throws Exception {
        givenRules(rule(0, null, 72));
        givenTorrents(torrent("hashA", "/data/Show", 10 * GB, 100 * 3600),
                torrent("hashB", "/data/Show", 10 * GB, 200 * 3600));
        org.mockito.Mockito.doThrow(new java.io.IOException("boom"))
                .when(client).deleteTorrent(any(), eq("hashA"), eq(false));

        CleanSummary summary = service.clean(downloader());

        assertEquals(0, summary.getDeletedGroups());
        assertEquals(1, summary.getFailedGroups());
        verify(client, never()).deleteTorrent(any(), eq("hashB"), anyBoolean());
    }

    // ---------- 护栏 ----------

    @Test
    void H和R考核中的种子受保护() throws Exception {
        givenRules(rule(0, null, 0));
        givenTorrents(torrent("hash1", "/data/A", 10 * GB, 999 * 3600));
        PtDownloadRecordPlus pending = new PtDownloadRecordPlus();
        pending.setTorrentHash("HASH1");
        pending.setHrState(HitAndRunState.PENDING.value());
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(pending));

        assertEquals(CleanSkipReason.HIT_AND_RUN_PENDING, service.evaluate(downloader()).get(0).getSkipReason());
    }

    @Test
    void 还有集停在在途的种子受保护() throws Exception {
        // 种子下完 ≠ 活干完：文件还要传到网盘，大文件跨天是常态。
        // 这段时间删掉保种文件，会造出一个"下载记录显示成功、媒体库里永远不出现"的集
        givenRules(rule(0, null, 0));
        givenTorrents(torrent("hash1", "/data/A", 10 * GB, 999 * 3600));
        PtSubscriptionEpisodePlus inFlight = new PtSubscriptionEpisodePlus();
        inFlight.setDownloadId(55);
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(inFlight));
        PtDownloadRecordPlus record = new PtDownloadRecordPlus();
        record.setId(55);
        record.setTorrentHash("hash1");
        when(recordService.listByIds(any())).thenReturn(List.of(record));

        assertEquals(CleanSkipReason.UPLOAD_PENDING, service.evaluate(downloader()).get(0).getSkipReason());
    }

    @Test
    void 排除标签命中时不删() throws Exception {
        givenRules(rule(0, null, 0));
        DownloaderTorrent keep = torrent("hash1", "/data/A", 10 * GB, 999 * 3600);
        keep.setTags("osr-pt,keep");
        givenTorrents(keep);
        PtDownloaderPlus downloader = downloader();
        downloader.setAutoDeleteExcludeTags("KEEP , 手动保留");

        assertEquals(CleanSkipReason.EXCLUDED_TAG, service.evaluate(downloader).get(0).getSkipReason());
    }

    @Test
    void 每轮删除上限生效且超出的组改判为留到下一轮() throws Exception {
        givenRules(rule(0, null, 0));
        givenTorrents(torrent("a", "/data/a", 30 * GB, 999 * 3600),
                torrent("b", "/data/b", 20 * GB, 999 * 3600),
                torrent("c", "/data/c", 10 * GB, 999 * 3600));
        PtDownloaderPlus downloader = downloader();
        downloader.setAutoDeleteMaxPerRound(2);

        List<CleanGroupDecision> decisions = service.evaluate(downloader);

        // 按体积降序，前两个可删，第三个改判为 ROUND_LIMIT
        assertTrue(decisions.get(0).isDeletable());
        assertTrue(decisions.get(1).isDeletable());
        assertFalse(decisions.get(2).isDeletable());
        assertEquals(CleanSkipReason.ROUND_LIMIT, decisions.get(2).getSkipReason());
    }

    @Test
    void 未开启自动删种的下载器不参与全局清理() {
        PtDownloaderPlus off = downloader();
        off.setAutoDeleteEnabled("0");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(off));

        assertTrue(service.cleanAll().isEmpty());
    }

    // ---------- 按空间删种 ----------

    private PtDownloaderPlus spaceDownloader(double belowGb) {
        PtDownloaderPlus downloader = downloader();
        downloader.setAutoDeleteFreeBelowGb(BigDecimal.valueOf(belowGb));
        return downloader;
    }

    @Test
    void 按空间删种_剩余空间充足时一个都不删() throws Exception {
        givenRules(rule(0, null, 0));
        givenTorrents(torrent("a", "/data/A", 10 * GB, 3600));
        when(client.freeSpace(any())).thenReturn(200 * GB);

        List<CleanGroupDecision> decisions = service.evaluate(spaceDownloader(100));

        assertEquals(CleanSkipReason.SPACE_SUFFICIENT, decisions.get(0).getSkipReason());
        assertEquals(0, service.clean(spaceDownloader(100)).getDeletedGroups());
    }

    /** 从大到小删、腾够就停：缺 25G 时删掉 30G 那组就够了，10G 那组留到下次 */
    @Test
    void 按空间删种_从大到小删到腾够为止() throws Exception {
        givenRules(rule(0, null, 0));
        givenTorrents(torrent("small", "/data/S", 10 * GB, 3600), torrent("big", "/data/B", 30 * GB, 3600));
        when(client.freeSpace(any())).thenReturn(75 * GB);

        CleanSummary summary = service.clean(spaceDownloader(100));

        assertEquals(1, summary.getDeletedGroups());
        verify(client).deleteTorrent(any(), eq("big"), eq(true));
        verify(client, never()).deleteTorrent(any(), eq("small"), anyBoolean());
        assertEquals(CleanSkipReason.SPACE_TARGET_REACHED, service.evaluate(spaceDownloader(100)).get(1).getSkipReason());
    }

    /** 只删种子不删文件的组腾不出空间，不能拿它凑数提前收手 */
    @Test
    void 按空间删种_不删文件的组不计入腾出量() throws Exception {
        PtCleanRulePlus keepFiles = rule(20, null, 0);
        keepFiles.setDeleteFiles("0");
        givenRules(keepFiles, rule(0, 20.0, 0));
        givenTorrents(torrent("noFiles", "/data/N", 50 * GB, 3600), torrent("withFiles", "/data/W", 10 * GB, 3600));
        when(client.freeSpace(any())).thenReturn(95 * GB);

        List<CleanGroupDecision> decisions = service.evaluate(spaceDownloader(100));

        assertTrue(decisions.stream().allMatch(CleanGroupDecision::isDeletable),
                "50G 那组不删文件、腾不出空间，10G 那组仍要删");
    }

    /** 判据缺失不动手 */
    @Test
    void 按空间删种_读不到剩余空间时一个都不删() throws Exception {
        givenRules(rule(0, null, 0));
        givenTorrents(torrent("a", "/data/A", 10 * GB, 3600));
        when(client.freeSpace(any())).thenReturn(null);

        assertEquals(CleanSkipReason.FREE_SPACE_UNKNOWN, service.evaluate(spaceDownloader(100)).get(0).getSkipReason());

        when(client.freeSpace(any())).thenThrow(new java.io.IOException("timeout"));
        assertEquals(CleanSkipReason.FREE_SPACE_UNKNOWN, service.evaluate(spaceDownloader(100)).get(0).getSkipReason());
    }

    @Test
    void 没设删种线时不读剩余空间_行为与引入前一致() throws Exception {
        givenRules(rule(0, null, 0));
        givenTorrents(torrent("a", "/data/A", 10 * GB, 3600));

        assertTrue(service.evaluate(downloader()).get(0).isDeletable());
        verify(client, never()).freeSpace(any());
    }

    // ---------- 夹具 ----------

    private void givenRules(PtCleanRulePlus... rules) {
        when(ruleService.listEnabledByDownloader(any())).thenReturn(List.of(rules));
    }

    private void givenTorrents(DownloaderTorrent... torrents) throws Exception {
        when(client.listAll(any())).thenReturn(List.of(torrents));
    }

    private PtDownloaderPlus downloader() {
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setName("保种机");
        downloader.setType("QBITTORRENT");
        downloader.setEnabled("1");
        downloader.setAutoDeleteEnabled("1");
        downloader.setAutoDeleteMaxPerRound(0);
        return downloader;
    }

    private PtCleanRulePlus rule(double minGb, Double maxGb, int minSeedHours) {
        PtCleanRulePlus rule = new PtCleanRulePlus();
        rule.setMinSizeGb(BigDecimal.valueOf(minGb));
        rule.setMaxSizeGb(maxGb == null ? null : BigDecimal.valueOf(maxGb));
        rule.setMinSeedHours(minSeedHours);
        rule.setDeleteFiles("1");
        rule.setEnabled("1");
        return rule;
    }

    private DownloaderTorrent torrent(String hash, String contentPath, long size, long seedingSeconds) {
        DownloaderTorrent torrent = new DownloaderTorrent();
        torrent.setHash(hash);
        torrent.setName(hash + "-name");
        torrent.setContentPath(contentPath);
        torrent.setSize(size);
        torrent.setSeedingSeconds(seedingSeconds);
        torrent.setProgress(1.0);
        torrent.setRawState("stalledUP");
        return torrent;
    }
}
