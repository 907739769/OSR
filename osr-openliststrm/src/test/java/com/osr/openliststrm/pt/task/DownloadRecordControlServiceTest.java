package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.task.dto.DownloadDeleteResult;
import com.osr.openliststrm.pt.task.dto.DownloadLiveView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下载记录页对下载器的直接操作：实时速度、暂停 / 继续、删除。
 * 删除那几条边界（H&R、已完成不删文件、不按名字认种子）是这组用例的重点。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DownloadRecordControlServiceTest {

    @Mock private IPtDownloadRecordPlusService recordService;
    @Mock private IPtDownloaderPlusService downloaderService;
    @Mock private IPtIndexerPlusService indexerService;
    @Mock private DownloaderClientFactory clientFactory;
    @Mock private IDownloaderClient client;
    @Mock private DownloadTrackService trackService;

    private DownloadRecordControlService service;

    @BeforeEach
    void setUp() {
        service = new DownloadRecordControlService(recordService, downloaderService, indexerService,
                clientFactory, trackService);
        when(clientFactory.get(any())).thenReturn(client);
        when(downloaderService.getById(1)).thenReturn(downloader(1));
    }

    private PtDownloaderPlus downloader(int id) {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(id);
        d.setName("qb" + id);
        d.setTag("osr-pt");
        return d;
    }

    private PtDownloadRecordPlus record(int id, String state) {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(id);
        r.setSubId(10);
        r.setEpisode(1);
        r.setDownloaderId(1);
        r.setIndexerId(7);
        r.setState(state);
        r.setTrackingTag("osr-pt-" + id);
        r.setTitle("Show.S01E01");
        when(recordService.getById(id)).thenReturn(r);
        return r;
    }

    private DownloaderTorrent torrent(String hash, String tags, double progress) {
        DownloaderTorrent t = new DownloaderTorrent();
        t.setHash(hash);
        t.setTags(tags);
        t.setProgress(progress);
        return t;
    }

    /** 让下载器按这条记录的跟踪标签查得到这个种子 */
    private void inDownloader(PtDownloadRecordPlus r, DownloaderTorrent t) throws IOException {
        when(client.listByTag(any(), eq(r.getTrackingTag()))).thenReturn(List.of(t));
    }

    private PtIndexerPlus hrIndexer() {
        PtIndexerPlus i = new PtIndexerPlus();
        i.setId(7);
        i.setHrEnabled("1");
        i.setHrSeedHours(72);
        return i;
    }

    // ---------- 实时状态 ----------

    @Test
    void 实时状态_按下载器分组只拉一次_并按跟踪标签对上记录() throws Exception {
        PtDownloadRecordPlus a = record(1, "DOWNLOADING");
        PtDownloadRecordPlus b = record(2, "PUSHED");
        PtDownloadRecordPlus done = record(3, "COMPLETED");
        when(recordService.listByIds(any())).thenReturn(List.of(a, b, done));
        when(downloaderService.listByIds(any())).thenReturn(List.of(downloader(1)));
        DownloaderTorrent ta = torrent("ha", "osr-pt,osr-pt-1", 0.4);
        ta.setDownloadSpeed(2_000_000);
        ta.setEtaSeconds(600L);
        when(client.listByTag(any(), eq("osr-pt"))).thenReturn(List.of(ta));

        Map<Integer, DownloadLiveView> live = service.live(List.of(1, 2, 3)).stream()
                .collect(Collectors.toMap(DownloadLiveView::getId, v -> v));

        verify(client).listByTag(any(), eq("osr-pt"));
        assertEquals(2, live.size(), "已完成的记录不返回");
        assertTrue(live.get(1).isFound());
        assertEquals(2_000_000, live.get(1).getDownloadSpeed());
        assertEquals(600L, live.get(1).getEtaSeconds());
        assertFalse(live.get(2).isFound(), "下载器里还没有它（仍在解析元数据）");
    }

    @Test
    void 实时状态_下载器连不上_只报找不到不抛异常() throws Exception {
        PtDownloadRecordPlus a = record(1, "DOWNLOADING");
        when(recordService.listByIds(any())).thenReturn(List.of(a));
        when(downloaderService.listByIds(any())).thenReturn(List.of(downloader(1)));
        when(client.listByTag(any(), anyString())).thenThrow(new IOException("connection refused"));

        List<DownloadLiveView> live = service.live(List.of(1));

        assertEquals(1, live.size());
        assertFalse(live.get(0).isFound());
    }

    // ---------- 暂停 / 继续 ----------

    @Test
    void 暂停_只允许下载中() {
        record(1, "PUSHED");
        assertThrows(IllegalArgumentException.class, () -> service.pause(1));
    }

    @Test
    void 暂停_下载器里其实已经下完了_拒绝以免中断做种() throws Exception {
        PtDownloadRecordPlus r = record(1, "DOWNLOADING");
        inDownloader(r, torrent("h", "osr-pt,osr-pt-1", 1.0));

        assertThrows(IllegalArgumentException.class, () -> service.pause(1));
        verify(client, never()).pauseTorrent(any(), any());
    }

    @Test
    void 暂停_成功后打上暂停标记() throws Exception {
        PtDownloadRecordPlus r = record(1, "DOWNLOADING");
        inDownloader(r, torrent("h", "osr-pt,osr-pt-1", 0.3));
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);

        service.pause(1);

        verify(client).pauseTorrent(any(), eq("h"));
        verify(recordService).update(org.mockito.ArgumentMatchers.argThat(
                (PtDownloadRecordPlus s) -> s != null && s.getUserPausedTime() != null), any(Wrapper.class));
    }

    @Test
    void 暂停_标记落空时撤回下载器里的暂停() throws Exception {
        // 这一瞬间记录被追踪轮次改了状态：不能留一个没有标记、却停在下载器里的种子
        PtDownloadRecordPlus r = record(1, "DOWNLOADING");
        inDownloader(r, torrent("h", "osr-pt,osr-pt-1", 0.3));
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.pause(1));
        verify(client).pauseTorrent(any(), eq("h"));
        verify(client).resumeTorrent(any(), eq("h"));
    }

    @Test
    void 继续_启动种子并撤销暂停标记() throws Exception {
        PtDownloadRecordPlus r = record(1, "DOWNLOADING");
        r.setUserPausedTime(new Date(System.currentTimeMillis() - 60_000));
        r.setUserPausedSeconds(0L);
        inDownloader(r, torrent("h", "osr-pt,osr-pt-1", 0.3));
        when(recordService.update(isNull(), any(Wrapper.class))).thenReturn(true);

        service.resume(1);

        verify(client).resumeTorrent(any(), eq("h"));
        verify(recordService).update(isNull(), any(Wrapper.class));
        assertNull(r.getUserPausedTime());
    }

    @Test
    void 继续_没有暂停的记录拒绝() {
        record(1, "DOWNLOADING");
        assertThrows(IllegalArgumentException.class, () -> service.resume(1));
    }

    // ---------- 删除 ----------

    @Test
    void 删除_H和R考核中一律拒绝_连下载器都不碰() throws Exception {
        PtDownloadRecordPlus r = record(1, "COMPLETED");
        r.setHrState(HitAndRunState.PENDING.value());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.deleteTorrent(1, false));
        assertTrue(e.getMessage().contains("H&R"));
        verify(client, never()).deleteTorrent(any(), any(), anyBoolean());
    }

    @Test
    void 删除_站点有H和R_已下完却没有达标记录_拒绝() throws Exception {
        // 站点可能是后来才开的 H&R，或者落库状态还没追上：没有证据证明它安全
        PtDownloadRecordPlus r = record(1, "COMPLETED");
        DownloaderTorrent t = torrent("h", "osr-pt,osr-pt-1", 1.0);
        inDownloader(r, t);
        when(indexerService.getById(7)).thenReturn(hrIndexer());

        assertThrows(IllegalArgumentException.class, () -> service.deleteTorrent(1, false));
        verify(client, never()).deleteTorrent(any(), any(), anyBoolean());
    }

    @Test
    void 删除_H和R已达标_可以移除任务() throws Exception {
        PtDownloadRecordPlus r = record(1, "COMPLETED");
        r.setHrState(HitAndRunState.SATISFIED.value());
        inDownloader(r, torrent("h", "osr-pt,osr-pt-1", 1.0));
        when(indexerService.getById(7)).thenReturn(hrIndexer());

        DownloadDeleteResult result = service.deleteTorrent(1, false);

        verify(client).deleteTorrent(any(), eq("h"), eq(false));
        assertFalse(result.isRecordFailed(), "已完成的记录只动下载器，记录本身不变");
        verify(trackService, never()).failByUser(any());
    }

    @Test
    void 删除_已下完的种子不允许连文件一起删() throws Exception {
        // 文件可能正被媒体库 / STRM 引用，或被辅种的兄弟种子共用
        PtDownloadRecordPlus r = record(1, "COMPLETED");
        inDownloader(r, torrent("h", "osr-pt,osr-pt-1", 1.0));

        assertThrows(IllegalArgumentException.class, () -> service.deleteTorrent(1, true));
        verify(client, never()).deleteTorrent(any(), any(), anyBoolean());
    }

    @Test
    void 删除_下载中的记录_删种并判失败回退集() throws Exception {
        PtDownloadRecordPlus r = record(1, "DOWNLOADING");
        inDownloader(r, torrent("h", "osr-pt,osr-pt-1", 0.3));
        when(indexerService.getById(7)).thenReturn(hrIndexer());
        when(trackService.failByUser(r)).thenReturn(true);

        DownloadDeleteResult result = service.deleteTorrent(1, true);

        verify(client).deleteTorrent(any(), eq("h"), eq(true));
        verify(trackService).failByUser(r);
        assertTrue(result.isRecordFailed());
        assertTrue(result.isFilesDeleted());
    }

    @Test
    void 删除_删种失败时不改记录() throws Exception {
        // 先删种、后改记录：反过来会留下一条「已失败」却还在下载器里跑的种子
        PtDownloadRecordPlus r = record(1, "DOWNLOADING");
        inDownloader(r, torrent("h", "osr-pt,osr-pt-1", 0.3));
        doThrow(new IOException("403")).when(client).deleteTorrent(any(), any(), anyBoolean());

        assertThrows(IllegalArgumentException.class, () -> service.deleteTorrent(1, false));
        verify(trackService, never()).failByUser(any());
    }

    @Test
    void 删除_标签认不出时按hash找_但绝不按种子名找() throws Exception {
        // 辅种的兄弟种子名字往往逐字一致，按名字认就可能删错一个
        PtDownloadRecordPlus r = record(1, "FAILED");
        r.setTorrentHash("ABC");
        when(client.listByTag(any(), anyString())).thenReturn(List.of());
        when(client.getTorrent(any(), eq("abc"))).thenReturn(torrent("abc", "", 0.2));

        service.deleteTorrent(1, false);

        verify(client).deleteTorrent(any(), eq("abc"), eq(false));
    }

    @Test
    void 删除_下载器里找不到种子_报错() throws Exception {
        record(1, "FAILED");
        when(client.listByTag(any(), anyString())).thenReturn(List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.deleteTorrent(1, false));
        assertTrue(e.getMessage().contains("找不到"));
    }
}
