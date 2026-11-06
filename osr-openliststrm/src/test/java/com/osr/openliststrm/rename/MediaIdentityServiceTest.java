package com.osr.openliststrm.rename;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.mybatisplus.domain.*;
import com.osr.openliststrm.mybatisplus.mapper.MediaIdentityBindingPlusMapper;
import com.osr.openliststrm.mybatisplus.service.*;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaIdentityServiceTest {
    @TempDir Path temp;
    private final MediaIdentityBindingPlusMapper mapper = mock(MediaIdentityBindingPlusMapper.class);
    private final IOpenlistStrmPlusService strm = mock(IOpenlistStrmPlusService.class);
    private final DownloaderClientFactory clients = mock(DownloaderClientFactory.class);
    private final IDownloaderClient client = mock(IDownloaderClient.class);
    private final OpenListHelper helper = mock(OpenListHelper.class);
    private final OpenlistConfig config = mock(OpenlistConfig.class);
    private final IPtDownloadRecordPlusService downloads = mock(IPtDownloadRecordPlusService.class);
    private final IPtDownloaderPlusService downloaders = mock(IPtDownloaderPlusService.class);
    private final IPtSubscriptionPlusService subscriptions = mock(IPtSubscriptionPlusService.class);
    private final List<MediaIdentityBindingPlus> rows = new ArrayList<>();
    private final List<PtDownloadRecordPlus> active = new ArrayList<>();
    private final Map<Integer, PtDownloadRecordPlus> records = new HashMap<>();
    private final Map<Integer, PtSubscriptionPlus> subs = new HashMap<>();
    private MediaIdentityService service;

    private MediaIdentityService service() {
        return new MediaIdentityService(mapper, strm, clients, helper, config,
                new MediaIdentityBindingStore(mapper), downloads, downloaders, subscriptions);
    }

    @BeforeEach
    void setup() {
        service = service();
        when(config.getOpenListUrl()).thenReturn("https://openlist.example");
        when(config.getOpenListStrmEncode()).thenReturn("1");
        when(clients.get(any())).thenReturn(client);
        when(helper.isVideo(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).endsWith(".mkv"));
        when(mapper.selectById(anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getBindingKey().equals(i.getArgument(0))).findFirst().orElse(null));
        when(mapper.selectList(any())).thenAnswer(i -> {
            QueryWrapper<?> q = i.getArgument(0);
            q.getSqlSegment();
            Collection<Object> values = q.getParamNameValuePairs().values();
            return rows.stream().filter(r -> values.contains(r.getSourceKind()))
                    .filter(r -> "SNAPSHOT".equals(r.getSourceKind())
                            ? values.contains(r.getDownloadId()) : values.contains(r.getFileNameHash())).toList();
        });
        when(mapper.delete(any(Wrapper.class))).thenAnswer(i -> {
            QueryWrapper<?> q = i.getArgument(0);
            q.getSqlSegment();
            rows.removeIf(r -> q.getParamNameValuePairs().containsValue(r.getDownloadId()));
            return 1;
        });
        doAnswer(i -> {
            MediaIdentityBindingPlus row = i.getArgument(0);
            rows.removeIf(r -> r.getBindingKey().equals(row.getBindingKey()));
            rows.add(row);
            return null;
        }).when(mapper).upsert(any());
        when(downloads.list(any(Wrapper.class))).thenAnswer(i -> List.copyOf(active));
        when(downloads.getById(anyInt())).thenAnswer(i -> records.get(i.getArgument(0)));
        when(subscriptions.getById(anyInt())).thenAnswer(i -> subs.get(i.getArgument(0)));
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        when(downloaders.getById(1)).thenReturn(downloader);
    }

    private DownloaderTorrentFile file(String name, boolean wanted) {
        DownloaderTorrentFile f = new DownloaderTorrentFile();
        f.setName(name); f.setSize(4); f.setWanted(wanted);
        return f;
    }

    private DownloaderTorrent prepare(int recordId, String tmdbId, List<DownloaderTorrentFile> files) throws Exception {
        PtDownloadRecordPlus record = new PtDownloadRecordPlus();
        record.setId(recordId); record.setSubId(recordId); record.setDownloaderId(1);
        record.setTrackingTag("osr-pt-" + recordId); record.setPushedTime(new Date(1000));
        record.setFilesSelected(true);
        records.put(recordId, record);
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setTmdbId(tmdbId); sub.setMediaType("TV"); sub.setSeason(3);
        subs.put(recordId, sub);
        DownloaderTorrent torrent = new DownloaderTorrent();
        torrent.setSavePath("/downloads"); torrent.setHash("hash" + recordId); torrent.setProgress(1);
        when(client.listFiles(any(), eq(torrent.getHash()))).thenReturn(files);
        when(client.listByTag(any(), eq(record.getTrackingTag()))).thenReturn(List.of(torrent));
        return torrent;
    }

    private void capture(int recordId, String tmdbId, List<DownloaderTorrentFile> files) throws Exception {
        DownloaderTorrent torrent = prepare(recordId, tmdbId, files);
        service.captureDownload(records.get(recordId), downloaders.getById(1), torrent, subs.get(recordId));
    }

    private List<MediaIdentityBindingPlus> files() {
        return rows.stream().filter(r -> "DOWNLOAD".equals(r.getSourceKind())).toList();
    }

    private Path video(String relative, int size) throws Exception {
        Path p = temp.resolve(relative);
        Files.createDirectories(p.getParent()); Files.write(p, new byte[size]); return p;
    }

    @Test
    void 按路径大小沿用订阅_跳过未选文件字幕_保留具体文件季号() throws Exception {
        capture(1, "79481", List.of(file("Pack/Show.S03E02.mkv", true), file("Pack/Show.S03E03.mkv", false),
                file("Pack/Show.S02E01.mkv", true), file("Pack/sub.srt", true)));
        assertEquals(2, files().size());
        assertEquals(new MediaIdentity("79481", "tv", "3", null), service.resolve(video("cloud/Pack/Show.S03E02.mkv", 4)));
        assertEquals("2", service.resolve(video("cloud/Pack/Show.S02E01.mkv", 4)).season());
        assertNull(service.resolve(video("cloud/Other/Show.S03E02.mkv", 4)));
        Path partial = video("cloud/Pack/Show.S03E02.mkv", 3);
        assertThrows(MediaIdentityPendingException.class, () -> service.resolve(partial));
    }

    @Test
    void 首次轮询前下载完成_主动取清单返回绑定而不是让调用方猜片() throws Exception {
        prepare(1, "79481", List.of(file("Pack/Wrong.S03E02.mkv", true)));
        records.get(1).setFilesSelected(false);
        active.add(records.get(1));
        Path source = video("cloud/Pack/Wrong.S03E02.mkv", 4);
        assertEquals("79481", service.resolve(source).tmdbId());
        assertEquals("79481", service.resolve(source).tmdbId());
        verify(client, times(1)).listByTag(any(), eq("osr-pt-1"));
    }

    @Test
    void 尚无元数据必须等待_文件选择未完成只延后相关文件() throws Exception {
        DownloaderTorrent torrent = prepare(1, "79481", List.of());
        records.get(1).setFilesSelected(false); active.add(records.get(1));
        Path source = video("Pack/Show.mkv", 4);
        assertThrows(MediaIdentityPendingException.class, () -> service.resolve(source));
        when(client.listFiles(any(), eq("hash1"))).thenReturn(List.of(file("Pack/Show.mkv", true)));
        torrent.setProgress(.5);
        assertThrows(MediaIdentityPendingException.class, () -> service.resolve(source));
        assertNull(service.resolve(video("Other/Unrelated.mkv", 4)));
        torrent.setProgress(1);
        assertEquals("79481", service.resolve(source).tmdbId());
    }

    @Test
    void 失败下载ID复用后刷新作品和整份文件清单_删掉旧绑定() throws Exception {
        capture(1, "100", List.of(file("Pack/Show.S03E01.mkv", true)));
        PtDownloadRecordPlus reused = records.get(1);
        reused.setSubId(99); reused.setPushedTime(new Date(2000));
        PtSubscriptionPlus corrected = new PtSubscriptionPlus();
        corrected.setTmdbId("200"); corrected.setMediaType("TV"); corrected.setSeason(3);
        subs.put(99, corrected);
        when(client.listFiles(any(), eq("hash1"))).thenReturn(List.of(file("Pack/Show.S03E02.mkv", true)));
        active.add(reused);
        assertEquals("200", service.resolve(video("Pack/Show.S03E02.mkv", 4)).tmdbId());
        assertEquals(1, files().size());
        assertNull(service.resolve(video("Pack/Show.S03E01.mkv", 4)));
    }

    @Test
    void 同次推送重新选文件也完整替换_完成时补写hash不会使快照失效() throws Exception {
        DownloaderTorrent torrent = prepare(1, "100", List.of(file("Pack/Show.S03E01.mkv", true)));
        service.captureDownloadFiles(records.get(1), torrent, subs.get(1), List.of(file("Pack/Show.S03E01.mkv", true)));
        service.captureDownloadFiles(records.get(1), torrent, subs.get(1), List.of(file("Pack/Show.S03E02.mkv", true)));
        assertEquals(1, files().size());
        assertTrue(files().getFirst().getRelativePath().endsWith("E02.mkv"));
        records.get(1).setTorrentHash("hash1");
        service.captureDownload(records.get(1), downloaders.getById(1), torrent, subs.get(1));
        verify(client, never()).listFiles(any(), anyString());
    }

    @Test
    void STRM原视频大小晚落库可恢复_不依赖STRM文件发生变化() throws Exception {
        capture(1, "79481", List.of(file("Pack/Show S03E02.mkv", true)));
        Path source = temp.resolve("Show.strm");
        Files.writeString(source, "https://openlist.example/d/remote/Pack/Show%20S03E02.mkv");
        when(strm.list(any(Wrapper.class))).thenReturn(List.of());
        assertThrows(MediaIdentityPendingException.class, () -> service.resolve(source));
        OpenlistStrmPlus record = new OpenlistStrmPlus(); record.setFileSize(4L);
        when(strm.list(any(Wrapper.class))).thenReturn(List.of(record));
        assertEquals("79481", service.resolve(source).tmdbId());
        when(config.getOpenListStrmEncode()).thenReturn("0");
        Files.writeString(source, "https://openlist.example/d/remote/Pack/Show S03E02.mkv");
        assertEquals("79481", service.resolve(source).tmdbId());
    }

    @Test
    void 冲突停止_人工指定按源路径优先且不受待绑定下载影响() throws Exception {
        capture(1, "79481", List.of(file("Pack/Show.mkv", true)));
        capture(2, "123", List.of(file("Pack/Show.mkv", true)));
        Path source = video("Pack/Show.mkv", 4);
        assertThrows(IllegalStateException.class, () -> service.resolve(source));
        service.pin(source, new MediaIdentity("456", "movie", "3", "2"));
        active.add(records.get(1));
        assertEquals(new MediaIdentity("456", "movie", null, null), service().resolve(source));
        verify(client, never()).listByTag(any(), anyString());
    }

    @Test
    void 清单缺失不保存半份快照_非法路径不写文件绑定() throws Exception {
        assertThrows(java.io.IOException.class, () -> capture(1, "79481", List.of()));
        assertTrue(rows.isEmpty());
        capture(2, "79481", List.of(file("../Show.mkv", true), file("/Show.mkv", true)));
        assertTrue(files().isEmpty());
    }
}
