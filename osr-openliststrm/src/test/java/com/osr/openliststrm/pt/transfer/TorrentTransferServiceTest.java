package com.osr.openliststrm.pt.transfer;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRulePlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRulePlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.AddTorrentOutcome;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.task.HitAndRunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转移做种的判定与状态机。
 * <p>
 * 这里最要紧的两条不是"功能对不对"，而是"出错时会不会毁数据"：
 * 撤销目标端种子必须 {@code deleteFiles=false}（那是源端正在做种的文件），
 * 以及校验没过时绝不能去删源端的种子。两者各有一条用例钉着。
 * </p>
 * <p>
 * 注意所有涉及下载器实例的打桩都用 {@link org.mockito.ArgumentMatchers#same}：
 * {@code *Plus} 实体没有自己的 equals，继承自 BaseEntity 的浅层 equals 会把
 * 两个未落库的实例判成相等，用默认匹配会让 source/target 的打桩互相覆盖。
 * </p>
 *
 * @author Jack
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TorrentTransferServiceTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final String HASH = "abc123";

    @Mock private IPtTransferRulePlusService ruleService;
    @Mock private IPtTransferRecordPlusService recordService;
    @Mock private IPtDownloaderPlusService downloaderService;
    @Mock private IPtDownloadRecordPlusService downloadRecordService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private DownloaderClientFactory clientFactory;
    @Mock private IDownloaderClient sourceClient;
    @Mock private IDownloaderClient targetClient;

    private TorrentTransferService service;
    private PtDownloaderPlus source;
    private PtDownloaderPlus target;

    @BeforeEach
    void setUp() throws Exception {
        service = new TorrentTransferService(ruleService, recordService, downloaderService,
                downloadRecordService, episodeService, clientFactory);

        source = downloader(1, "qb");
        target = downloader(2, "tr");
        when(downloaderService.getById(1)).thenReturn(source);
        when(downloaderService.getById(2)).thenReturn(target);
        when(clientFactory.get(same(source))).thenReturn(sourceClient);
        when(clientFactory.get(same(target))).thenReturn(targetClient);

        when(sourceClient.supportsExport()).thenReturn(true);
        when(sourceClient.exportTorrent(same(source), anyString())).thenReturn(new byte[]{1, 2, 3});
        when(targetClient.listAll(same(target))).thenReturn(List.of());
        when(recordService.listVerifying(any())).thenReturn(List.of());
        when(recordService.hasVerifying(any(), anyString())).thenReturn(false);
        when(downloadRecordService.list(any(Wrapper.class))).thenReturn(List.of());
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
    }

    // ---------- 判定 ----------

    @Test
    void 未下载完成的种子不转移() throws Exception {
        DownloaderTorrent torrent = torrent(0.5, 3600 * 100);
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent));

        TransferCandidate candidate = service.evaluate(rule()).get(0);

        assertFalse(candidate.isTransferable());
        assertEquals(TransferSkipReason.NOT_COMPLETED, candidate.getSkipReason());
    }

    @Test
    void 校验中的种子不转移() throws Exception {
        DownloaderTorrent torrent = torrent(1.0, 3600 * 100);
        torrent.setRawState("checkingUP");
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent));

        assertEquals(TransferSkipReason.BUSY_STATE, service.evaluate(rule()).get(0).getSkipReason());
    }

    @Test
    void 目标端已存在同hash的种子时跳过() throws Exception {
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent(1.0, 3600 * 100)));
        DownloaderTorrent onTarget = torrent(1.0, 0);
        when(targetClient.listAll(same(target))).thenReturn(List.of(onTarget));

        assertEquals(TransferSkipReason.ALREADY_ON_TARGET, service.evaluate(rule()).get(0).getSkipReason());
    }

    @Test
    void 做种时长未达标不转移() throws Exception {
        // 规则要求 72 小时，只做了 1 小时
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent(1.0, 3600)));

        assertEquals(TransferSkipReason.SEED_TIME_NOT_REACHED, service.evaluate(rule()).get(0).getSkipReason());
    }

    /**
     * H&R 考核中的种子不能搬：做种时长在目标端从零重新累计，而站点的 H&R 限额是以种子级
     * 限额下发到<b>原</b>下载器上的，不会跟着搬家。
     */
    @Test
    void H和R考核中的种子不转移() throws Exception {
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent(1.0, 3600 * 100)));
        PtDownloadRecordPlus record = new PtDownloadRecordPlus();
        record.setTorrentHash(HASH);
        record.setHrState(HitAndRunState.PENDING.value());
        when(downloadRecordService.list(any(Wrapper.class))).thenReturn(List.of(record));

        assertEquals(TransferSkipReason.HIT_AND_RUN_PENDING, service.evaluate(rule()).get(0).getSkipReason());
    }

    @Test
    void 排除标签一票否决() throws Exception {
        DownloaderTorrent torrent = torrent(1.0, 3600 * 100);
        torrent.setTags("keep,osr");
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent));
        PtTransferRulePlus rule = rule();
        rule.setExcludeTags("KEEP");

        assertEquals(TransferSkipReason.EXCLUDED_TAG, service.evaluate(rule).get(0).getSkipReason());
    }

    @Test
    void 超出单轮上限的种子留到下一轮() throws Exception {
        DownloaderTorrent big = torrent(1.0, 3600 * 100);
        big.setSize(50 * GB);
        DownloaderTorrent small = torrent(1.0, 3600 * 100);
        small.setHash("def456");
        small.setSize(1 * GB);
        when(sourceClient.listAll(same(source))).thenReturn(List.of(small, big));
        PtTransferRulePlus rule = rule();
        rule.setMaxPerRound(1);

        List<TransferCandidate> candidates = service.evaluate(rule);

        // 体积从大到小排序，大的拿到唯一的名额
        assertTrue(candidates.get(0).isTransferable());
        assertEquals(50 * GB, candidates.get(0).sizeBytes());
        assertEquals(TransferSkipReason.ROUND_LIMIT, candidates.get(1).getSkipReason());
    }

    // ---------- 发起转移 ----------

    @Test
    void 发起转移时以暂停态加种并触发校验() throws Exception {
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent(1.0, 3600 * 100)));
        when(targetClient.addTorrentFile(same(target), any(), anyString(), any(), anyBoolean()))
                .thenReturn(AddTorrentOutcome.ADDED);

        TransferSummary summary = service.runRule(rule());

        // paused 必须是 true：直接以运行态加进去，路径一旦对不上就会重新下载整个种子
        verify(targetClient).addTorrentFile(same(target), any(), eq("/downloads/x"), any(), eq(true));
        verify(targetClient).recheckTorrent(same(target), eq(HASH));
        ArgumentCaptor<PtTransferRecordPlus> captor = ArgumentCaptor.forClass(PtTransferRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(TransferState.VERIFYING.value(), captor.getValue().getState());
        assertEquals(1, summary.getStarted());
    }

    /**
     * 目标端本来就有这个种子时绝不能撤销它——那可能是用户自己加的任务。
     */
    @Test
    void 目标端报重复时不撤销种子() throws Exception {
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent(1.0, 3600 * 100)));
        when(targetClient.addTorrentFile(same(target), any(), anyString(), any(), anyBoolean()))
                .thenReturn(AddTorrentOutcome.DUPLICATE);

        TransferSummary summary = service.runRule(rule());

        verify(targetClient, never()).deleteTorrent(any(), anyString(), anyBoolean());
        verify(targetClient, never()).recheckTorrent(any(), anyString());
        assertEquals(1, summary.getSkipped());
    }

    @Test
    void 源下载器不支持导出时整条规则不做任何事() throws Exception {
        when(sourceClient.supportsExport()).thenReturn(false);
        when(sourceClient.listAll(same(source))).thenReturn(List.of(torrent(1.0, 3600 * 100)));

        TransferSummary summary = service.runRule(rule());

        assertTrue(summary.isExportUnsupported());
        verify(targetClient, never()).addTorrentFile(any(), any(), anyString(), any(), anyBoolean());
    }

    // ---------- 推进校验中的转移 ----------

    @Test
    void 校验通过后先启动目标端再删源端种子() throws Exception {
        when(recordService.listVerifying(any())).thenReturn(List.of(verifyingRecord()));
        when(sourceClient.listAll(same(source))).thenReturn(List.of());
        DownloaderTorrent onTarget = torrent(1.0, 0);
        when(targetClient.getTorrent(same(target), eq(HASH))).thenReturn(onTarget);

        TransferSummary summary = service.runRule(rule());

        // 顺序是硬要求：反过来的话中间那段窗口两边都不在做种
        InOrder order = inOrder(targetClient, sourceClient);
        order.verify(targetClient).resumeTorrent(same(target), eq(HASH));
        // deleteFiles 必须是 false：那份文件正是目标端刚接手做种的数据
        order.verify(sourceClient).deleteTorrent(same(source), eq(HASH), eq(false));
        assertEquals(1, summary.getCompleted());
    }

    @Test
    void 规则关掉删源种时只启动不删() throws Exception {
        when(recordService.listVerifying(any())).thenReturn(List.of(verifyingRecord()));
        when(sourceClient.listAll(same(source))).thenReturn(List.of());
        when(targetClient.getTorrent(same(target), eq(HASH))).thenReturn(torrent(1.0, 0));
        PtTransferRulePlus rule = rule();
        rule.setDeleteSource("0");

        service.runRule(rule);

        verify(targetClient).resumeTorrent(same(target), eq(HASH));
        verify(sourceClient, never()).deleteTorrent(any(), anyString(), anyBoolean());
    }

    /**
     * 校验跑完却没到 100%，说明目标下载器在它看到的路径下找不到这份数据（多半是路径映射配错）。
     * 必须把种子撤掉，否则它会把几十 GB 重新下一遍。
     */
    @Test
    void 校验未通过时撤销目标端种子且不删文件() throws Exception {
        when(recordService.listVerifying(any())).thenReturn(List.of(verifyingRecord()));
        when(sourceClient.listAll(same(source))).thenReturn(List.of());
        DownloaderTorrent onTarget = torrent(0.0, 0);
        onTarget.setChecking(false);
        when(targetClient.getTorrent(same(target), eq(HASH))).thenReturn(onTarget);

        TransferSummary summary = service.runRule(rule());

        verify(targetClient).deleteTorrent(same(target), eq(HASH), eq(false));
        // 源端种子一根汗毛都不能动
        verify(sourceClient, never()).deleteTorrent(any(), anyString(), anyBoolean());
        assertEquals(1, summary.getFailed());
    }

    @Test
    void 仍在校验且未超时时什么都不做() throws Exception {
        PtTransferRecordPlus record = verifyingRecord();
        when(recordService.listVerifying(any())).thenReturn(List.of(record));
        when(sourceClient.listAll(same(source))).thenReturn(List.of());
        DownloaderTorrent onTarget = torrent(0.3, 0);
        onTarget.setChecking(true);
        when(targetClient.getTorrent(same(target), eq(HASH))).thenReturn(onTarget);

        TransferSummary summary = service.runRule(rule());

        verify(targetClient, never()).deleteTorrent(any(), anyString(), anyBoolean());
        verify(targetClient, never()).resumeTorrent(any(), anyString());
        assertEquals(0, summary.getFailed());
        assertEquals(0, summary.getCompleted());
    }

    @Test
    void 校验超时后撤销目标端种子() throws Exception {
        PtTransferRecordPlus record = verifyingRecord();
        // 校验开始于 10 小时前，规则给的超时是 120 分钟
        record.setVerifyStartTime(new Date(System.currentTimeMillis() - 10 * 3600 * 1000L));
        when(recordService.listVerifying(any())).thenReturn(List.of(record));
        when(sourceClient.listAll(same(source))).thenReturn(List.of());
        DownloaderTorrent onTarget = torrent(0.3, 0);
        onTarget.setChecking(true);
        when(targetClient.getTorrent(same(target), eq(HASH))).thenReturn(onTarget);

        TransferSummary summary = service.runRule(rule());

        verify(targetClient).deleteTorrent(same(target), eq(HASH), eq(false));
        assertEquals(1, summary.getFailed());
    }

    /**
     * 查询目标端失败（网络抖动、下载器重启）时本轮不下结论。判成失败会在一次抖动里
     * 把一批本来正常的转移全部回滚掉。
     */
    @Test
    void 查询目标端失败时留到下一轮() throws Exception {
        when(recordService.listVerifying(any())).thenReturn(List.of(verifyingRecord()));
        when(sourceClient.listAll(same(source))).thenReturn(List.of());
        when(targetClient.getTorrent(same(target), eq(HASH))).thenThrow(new java.io.IOException("连不上"));

        TransferSummary summary = service.runRule(rule());

        verify(targetClient, never()).deleteTorrent(any(), anyString(), anyBoolean());
        assertEquals(0, summary.getFailed());
    }

    // ---------- 构造辅助 ----------

    private PtDownloaderPlus downloader(int id, String name) {
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(id);
        downloader.setName(name);
        downloader.setEnabled("1");
        return downloader;
    }

    private PtTransferRulePlus rule() {
        PtTransferRulePlus rule = new PtTransferRulePlus();
        rule.setId(10);
        rule.setName("qb搬tr");
        rule.setSourceDownloaderId(1);
        rule.setTargetDownloaderId(2);
        rule.setEnabled("1");
        rule.setMinSeedHours(72);
        rule.setDeleteSource("1");
        rule.setMaxPerRound(10);
        rule.setVerifyTimeoutMinutes(120);
        return rule;
    }

    private DownloaderTorrent torrent(double progress, long seedingSeconds) {
        DownloaderTorrent torrent = new DownloaderTorrent();
        torrent.setHash(HASH);
        torrent.setName("Some.Show.S01E01");
        torrent.setProgress(progress);
        torrent.setRawState("stalledUP");
        torrent.setSavePath("/downloads/x");
        torrent.setSeedingSeconds(seedingSeconds);
        torrent.setSize(10 * GB);
        return torrent;
    }

    private PtTransferRecordPlus verifyingRecord() {
        PtTransferRecordPlus record = new PtTransferRecordPlus();
        record.setId(100);
        record.setRuleId(10);
        record.setTorrentHash(HASH);
        record.setTorrentName("Some.Show.S01E01");
        record.setSizeBytes(10 * GB);
        record.setSourceDownloaderId(1);
        record.setTargetDownloaderId(2);
        record.setTargetSavePath("/downloads/x");
        record.setState(TransferState.VERIFYING.value());
        record.setSourceDeleted("0");
        record.setVerifyStartTime(new Date());
        return record;
    }
}
