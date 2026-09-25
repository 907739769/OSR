package com.osr.openliststrm.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.MediaExtensionProvider;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.helper.StrmHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmDirSnapshotPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmDirSnapshotPlusService;
import com.osr.openliststrm.service.StrmIncrementalScan;
import com.osr.openliststrm.service.StrmIncrementalScan.Mode;
import com.osr.openliststrm.service.StrmSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 增量扫描走一遍真实的 BFS：哪些目录被列、哪些被跳过、哪些记快照。
 * <p>
 * 目录树：
 * <pre>
 * /lib
 * ├── 电影A/            叶子，有 a.mkv
 * └── 三体/             非叶子
 *     └── Season 1/     叶子，有 e1.mkv
 * </pre>
 */
class StrmIncrementalScanFlowTest {

    @TempDir
    Path out;

    @Mock private OpenlistConfig config;
    @Mock private OpenlistApi openListApi;
    @Mock private OpenListHelper openListHelper;
    @Mock private StrmHelper strmHelper;
    @Mock private IOpenlistStrmDirSnapshotPlusService snapshotService;
    @Mock private MediaExtensionProvider mediaExtensions;

    private StrmServiceImpl service;
    private StrmSettings settings;
    private String sign;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = spy(new StrmServiceImpl());
        ReflectionTestUtils.setField(service, "config", config);
        ReflectionTestUtils.setField(service, "openListApi", openListApi);
        ReflectionTestUtils.setField(service, "openListHelper", openListHelper);
        ReflectionTestUtils.setField(service, "strmHelper", strmHelper);
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "mediaExtensions", mediaExtensions);
        doReturn(List.of()).when(service).loadExistingRecords(anyString());

        when(config.getOpenListUrl()).thenReturn("http://ol");
        when(config.getOpenListStrmEncode()).thenReturn("0");
        when(config.getTraversalConcurrency()).thenReturn(4);
        when(openListHelper.isVideo(anyString())).thenAnswer(inv -> inv.<String>getArgument(0).endsWith(".mkv"));
        when(openListHelper.isSrt(anyString())).thenAnswer(inv -> inv.<String>getArgument(0).endsWith(".srt"));
        when(mediaExtensions.videoExtensions()).thenReturn(Set.of("mkv"));
        when(mediaExtensions.subtitleExtensions()).thenReturn(Set.of("srt"));
        when(strmHelper.newRecord(anyString(), anyString(), anyString(), any(), any())).thenReturn(new com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus());

        settings = new StrmSettings(out.toString(), false, 0);
        sign = StrmIncrementalScan.settingsSign("http://ol", false, false, 0, out.toString(), Set.of("mkv"), Set.of("srt"));

        listing("/lib", dir("电影A", "m-A"), dir("三体", "m-3"));
        listing("/lib/电影A", file("a.mkv"));
        listing("/lib/三体", dir("Season 1", "m-S1"));
        listing("/lib/三体/Season 1", file("e1.mkv"));
    }

    private static JSONObject dir(String name, String modified) {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("is_dir", true);
        o.put("modified", modified);
        return o;
    }

    private static JSONObject file(String name) {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("is_dir", false);
        o.put("size", 1000L);
        return o;
    }

    private void listing(String path, JSONObject... items) {
        JSONObject data = new JSONObject();
        data.put("content", new JSONArray(List.of((Object[]) items)));
        JSONObject resp = new JSONObject();
        resp.put("code", 200);
        resp.put("data", data);
        when(openListApi.getOpenlist(eq(path), anyBoolean())).thenReturn(resp);
    }

    private OpenlistStrmDirSnapshotPlus snapshot(String path, String modified) {
        OpenlistStrmDirSnapshotPlus s = new OpenlistStrmDirSnapshotPlus();
        s.setDirPath(path);
        s.setModified(modified);
        s.setSettingsSign(sign);
        return s;
    }

    @SuppressWarnings("unchecked")
    private Set<String> upsertedPaths() {
        ArgumentCaptor<Collection<OpenlistStrmDirSnapshotPlus>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(snapshotService).upsert(captor.capture());
        return captor.getValue().stream().map(OpenlistStrmDirSnapshotPlus::getDirPath).collect(Collectors.toSet());
    }

    @Test
    void 全量模式_不碰快照表_行为与引入增量前一致() {
        StrmServiceImpl.ScanStats stats = service.scan("/lib", settings, Mode.FULL);

        assertEquals(4, stats.listedDirs());
        assertEquals(2, stats.generated());
        verifyNoInteractions(snapshotService);
    }

    /** 只有叶子目录记快照：非叶子目录（三体）与根目录每次都要列，否则它们底下的新季、新剧就看不见了 */
    @Test
    void 全量重建_只给叶子目录记快照_并清理已不存在的() {
        when(snapshotService.loadSubtree("/lib")).thenReturn(Map.of());

        service.scan("/lib", settings, Mode.FULL_REBUILD);

        assertEquals(Set.of("/lib/电影A", "/lib/三体/Season 1"), upsertedPaths());
        verify(snapshotService).purgeStale(eq("/lib"), any());
    }

    @Test
    void 增量_修改时间没变的叶子目录不列_非叶子目录照常列() {
        when(snapshotService.loadSubtree("/lib")).thenReturn(Map.of(
                "/lib/电影A", snapshot("/lib/电影A", "m-A"),
                "/lib/三体/Season 1", snapshot("/lib/三体/Season 1", "m-S1")));

        StrmServiceImpl.ScanStats stats = service.scan("/lib", settings, Mode.INCREMENTAL);

        verify(openListApi, never()).getOpenlist(eq("/lib/电影A"), anyBoolean());
        verify(openListApi, never()).getOpenlist(eq("/lib/三体/Season 1"), anyBoolean());
        verify(openListApi).getOpenlist(eq("/lib/三体"), anyBoolean());
        assertEquals(2, stats.skippedDirs());
        assertEquals(2, stats.listedDirs(), "只列了 /lib 与 /lib/三体");
        verify(snapshotService, never()).purgeStale(anyString(), any());
    }

    /** 已有季里新增一集：变的只有 Season 1 的修改时间，它必须被重新列出来 */
    @Test
    void 增量_叶子目录修改时间变了_照常列并生成() {
        when(snapshotService.loadSubtree("/lib")).thenReturn(Map.of(
                "/lib/电影A", snapshot("/lib/电影A", "m-A"),
                "/lib/三体/Season 1", snapshot("/lib/三体/Season 1", "m-S1-旧")));

        StrmServiceImpl.ScanStats stats = service.scan("/lib", settings, Mode.INCREMENTAL);

        verify(openListApi).getOpenlist(eq("/lib/三体/Season 1"), anyBoolean());
        assertEquals(1, stats.generated());
        assertTrue(out.resolve("lib").resolve("三体").resolve("Season 1").resolve("e1.strm").toFile().exists());
    }

    /** 字幕这次查不到（OpenList 无响应）：没处理完的目录不能记快照，否则下次增量会一直跳过它 */
    @Test
    void 有文件没处理完的目录_不记快照() {
        settings = new StrmSettings(out.toString(), true, 0);
        listing("/lib/电影A", file("a.mkv"), file("a.srt"));
        when(openListApi.getFile(anyString())).thenReturn(null);
        when(snapshotService.loadSubtree("/lib")).thenReturn(Map.of());

        service.scan("/lib", settings, Mode.FULL_REBUILD);

        assertEquals(Set.of("/lib/三体/Season 1"), upsertedPaths());
    }
}
