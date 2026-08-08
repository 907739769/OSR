package com.osr.openliststrm.orphan;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameOrphanPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameOrphanPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService;
import com.osr.openliststrm.rename.cleanup.RenameCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RenameOrphanScanServiceImplTest {

    @Mock
    private IRenameDetailPlusService renameDetailService;
    @Mock
    private IRenameOrphanPlusService renameOrphanService;
    @Mock
    private OpenlistApi openListApi;
    @Mock
    private OpenlistConfig config;
    @Mock
    private RenameCleanupService cleanupService;
    @Mock
    private IRenameTaskPlusService renameTaskService;
    @Mock
    private OpenListHelper openListHelper;

    private RenameOrphanScanServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RenameOrphanScanServiceImpl();
        service.renameDetailService = renameDetailService;
        service.renameOrphanService = renameOrphanService;
        service.openListApi = openListApi;
        service.config = config;
        service.cleanupService = cleanupService;
        service.renameTaskService = renameTaskService;
        service.openListHelper = openListHelper;
        // 真实实现按扩展名判定，这里照抄同样的语义，避免测试依赖字典表
        when(openListHelper.isStrm(anyString())).thenAnswer(i -> i.getArgument(0, String.class).toLowerCase().endsWith(".strm"));
        when(openListHelper.isVideo(anyString())).thenAnswer(i -> i.getArgument(0, String.class).toLowerCase().endsWith(".mkv"));
    }

    // ------------------------------------------------------------------
    // 清理 / 忽略
    // ------------------------------------------------------------------

    @Test
    void clean_原因是网盘源丢失_连产物带记录一起清() {
        RenameOrphanPlus orphan = new RenameOrphanPlus();
        orphan.setId(1);
        orphan.setDetailId(42);
        orphan.setReason(OrphanReason.SOURCE_MISSING);
        when(renameOrphanService.listByIds(List.of(1))).thenReturn(List.of(orphan));

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(42);
        detail.setNewPath("/nonexistent/dir/for/test");
        detail.setNewName("does-not-exist.strm");
        when(renameDetailService.getById(42)).thenReturn(detail);

        service.clean(List.of(1));

        verify(cleanupService).purge(List.of(detail), true);
        verify(renameOrphanService).updateBatchById(argThat(list -> {
            RenameOrphanPlus updated = list.iterator().next();
            return "1".equals(updated.getStatus()) && updated.getCleanTime() != null;
        }));
    }

    @Test
    void clean_原因是无主媒体文件_只删该文件不碰任何记录() {
        RenameOrphanPlus orphan = new RenameOrphanPlus();
        orphan.setId(7);
        orphan.setDetailId(null);
        orphan.setReason(OrphanReason.LOCAL_EXTRA);
        orphan.setNewPath(tempDir.toString());
        orphan.setNewName("无主.strm");
        when(renameOrphanService.listByIds(List.of(7))).thenReturn(List.of(orphan));

        service.clean(List.of(7));

        verify(cleanupService).purgeExtraFile(tempDir.resolve("无主.strm"));
        verify(renameDetailService, never()).getById(any());
        verify(cleanupService, never()).purge(any(), anyBoolean());
    }

    @Test
    void clean_原因是仅剩元数据的目录_走目录清理分支() {
        RenameOrphanPlus orphan = new RenameOrphanPlus();
        orphan.setId(8);
        orphan.setReason(OrphanReason.METADATA_ONLY);
        orphan.setNewPath(tempDir.toString());
        when(renameOrphanService.listByIds(List.of(8))).thenReturn(List.of(orphan));

        service.clean(List.of(8));

        verify(cleanupService).purgeMetadataOnlyDir(tempDir);
    }

    @Test
    void clean_单条失败不中断整批_失败项不标记为已清理() {
        RenameOrphanPlus bad = new RenameOrphanPlus();
        bad.setId(9);
        bad.setStatus("0");
        bad.setReason(OrphanReason.EMPTY_DIR);
        bad.setNewPath(tempDir.toString());
        RenameOrphanPlus good = new RenameOrphanPlus();
        good.setId(10);
        good.setStatus("0");
        good.setReason(OrphanReason.EMPTY_DIR);
        good.setNewPath(tempDir.toString());
        when(renameOrphanService.listByIds(List.of(9, 10))).thenReturn(List.of(bad, good));
        when(cleanupService.reclaimEmptyDirs(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(1);

        service.clean(List.of(9, 10));

        assertEquals("0", bad.getStatus(), "失败的那条不能被标记成已清理");
        assertEquals("1", good.getStatus());
    }

    @Test
    void ignore_批量标记为已忽略并写清理时间() {
        RenameOrphanPlus orphan = new RenameOrphanPlus();
        orphan.setId(5);
        orphan.setStatus("0");
        when(renameOrphanService.listByIds(List.of(5))).thenReturn(List.of(orphan));

        service.ignore(List.of(5));

        verify(renameOrphanService).updateBatchById(argThat(list -> {
            RenameOrphanPlus updated = list.iterator().next();
            return "2".equals(updated.getStatus()) && updated.getCleanTime() != null;
        }));
    }

    // ------------------------------------------------------------------
    // 正向扫描：记录 -> 文件
    // ------------------------------------------------------------------

    @Test
    void scan_本地文件不存在_判定为local_missing并插入孤儿记录() {
        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(1);
        detail.setStatus("1");
        detail.setNewPath(tempDir.resolve("missing-dir").toString());
        detail.setNewName("ghost.strm");
        detail.setTitle("Ghost");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));
        when(renameOrphanService.list()).thenReturn(List.of());

        service.scan();

        verify(renameOrphanService).save(argThat(o -> OrphanReason.LOCAL_MISSING.equals(o.getReason()) && o.getDetailId() == 1));
    }

    @Test
    void scan_本地文件不存在但该detail已有已忽略的孤儿记录_跳过不落库() {
        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(1);
        detail.setStatus("1");
        detail.setNewPath(tempDir.resolve("missing-dir").toString());
        detail.setNewName("ghost.strm");
        detail.setTitle("Ghost");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));

        RenameOrphanPlus ignored = new RenameOrphanPlus();
        ignored.setId(99);
        ignored.setDetailId(1);
        ignored.setStatus("2");
        ignored.setReason(OrphanReason.LOCAL_MISSING);
        when(renameOrphanService.list()).thenReturn(List.of(ignored));

        var summary = service.scan();

        verify(renameOrphanService, never()).save(any());
        verify(renameOrphanService, never()).updateById(any());
        assertEquals(0, summary.localMissing());
    }

    @Test
    void scan_本地文件存在但网盘源已删除_判定为source_missing() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("movies"));
        Path strmFile = dir.resolve("a.strm");
        when(config.getOpenListUrl()).thenReturn("http://alist.local");
        when(config.getOpenListStrmEncode()).thenReturn("0");
        Files.writeString(strmFile, "http://alist.local/d/movies/a.mkv", StandardCharsets.UTF_8);

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(2);
        detail.setStatus("1");
        detail.setNewPath(dir.toString());
        detail.setNewName("a.strm");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));
        when(renameOrphanService.list()).thenReturn(List.of());
        when(config.getTraversalConcurrency()).thenReturn(4);

        JSONObject dirListing = new JSONObject();
        dirListing.put("code", 200);
        JSONObject data = new JSONObject();
        data.put("content", new com.alibaba.fastjson2.JSONArray());
        dirListing.put("data", data);
        when(openListApi.getOpenlist(eq("/movies"), eq(false))).thenReturn(dirListing);

        service.scan();

        verify(renameOrphanService).save(argThat(o -> OrphanReason.SOURCE_MISSING.equals(o.getReason()) && o.getDetailId() == 2));
    }

    @Test
    void scan_核对网盘目录API调用失败_跳过本组不产生孤儿记录() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("movies3"));
        Path strmFile = dir.resolve("c.strm");
        when(config.getOpenListUrl()).thenReturn("http://alist.local");
        when(config.getOpenListStrmEncode()).thenReturn("0");
        Files.writeString(strmFile, "http://alist.local/d/movies3/c.mkv", StandardCharsets.UTF_8);

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(4);
        detail.setStatus("1");
        detail.setNewPath(dir.toString());
        detail.setNewName("c.strm");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));
        when(renameOrphanService.list()).thenReturn(List.of());
        when(config.getTraversalConcurrency()).thenReturn(4);

        // 模拟 openListApi 内部重试3次后仍失败，返回 null
        when(openListApi.getOpenlist(eq("/movies3"), eq(false))).thenReturn(null);

        service.scan();

        verify(renameOrphanService, never()).save(any());
        verify(renameOrphanService, never()).updateById(any());
    }

    @Test
    void scan_非strm的视频副本_本地文件在就算一致不去核对网盘() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("videos"));
        Files.writeString(dir.resolve("v.mkv"), "x");

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(6);
        detail.setStatus("1");
        detail.setNewPath(dir.toString());
        detail.setNewName("v.mkv");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(0, summary.localMissing());
        assertEquals(0, summary.sourceMissing());
        verify(openListApi, never()).getOpenlist(anyString(), anyBoolean());
    }

    // ------------------------------------------------------------------
    // 反向扫描：文件 -> 记录
    // ------------------------------------------------------------------

    /** 建出 <temp>/lib/电视剧/国产剧/某剧 (2024)/Season 01 并把 lib 配成任务目标目录 */
    private Path prepareLibrary() throws IOException {
        Path lib = tempDir.resolve("lib");
        Path seasonDir = lib.resolve("电视剧").resolve("国产剧").resolve("某剧 (2024)").resolve("Season 01");
        Files.createDirectories(seasonDir);
        RenameTaskPlus task = new RenameTaskPlus();
        task.setId(1);
        task.setTargetRoot(lib.toString());
        when(renameTaskService.list()).thenReturn(List.of(task));
        return seasonDir;
    }

    @Test
    void scan反向_媒体文件在库里但没有记录_判定为local_extra() throws IOException {
        Path seasonDir = prepareLibrary();
        Files.writeString(seasonDir.resolve("无主.strm"), "x");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(1, summary.localExtra());
        verify(renameOrphanService).save(argThat(o ->
                OrphanReason.LOCAL_EXTRA.equals(o.getReason())
                        && o.getDetailId() == null
                        && "无主.strm".equals(o.getNewName())));
    }

    @Test
    void scan反向_媒体文件有对应记录_不判为无主() throws IOException {
        Path seasonDir = prepareLibrary();
        Files.writeString(seasonDir.resolve("有主.strm"), "x");

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(1);
        detail.setStatus("1");
        detail.setNewPath(seasonDir.toString());
        detail.setNewName("有主.strm");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(0, summary.localExtra());
    }

    @Test
    void scan反向_目录里只剩nfo和图片_判定为metadata_only() throws IOException {
        Path seasonDir = prepareLibrary();
        Files.writeString(seasonDir.resolve("旧剧.S01E01.nfo"), "x");
        Files.writeString(seasonDir.resolve("season-poster.jpg"), "x");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(1, summary.metadataOnly(), "重命名换剧后留下的鬼剧集就是这个形状");
        verify(renameOrphanService).save(argThat(o ->
                OrphanReason.METADATA_ONLY.equals(o.getReason()) && o.getNewName() == null));
    }

    @Test
    void scan反向_完全空目录_判定为empty_dir且不牵连顶层骨架() throws IOException {
        prepareLibrary();
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        // Season 01 是空的；它的父目录含有子目录所以不算空，分类目录与顶层锚点更不该上榜
        assertEquals(1, summary.emptyDir());
        verify(renameOrphanService).save(argThat(o ->
                OrphanReason.EMPTY_DIR.equals(o.getReason()) && o.getNewPath().endsWith("Season 01")));
    }

    @Test
    void scan反向_上一轮记过这一轮没再发现_自动移出待处理列表() throws IOException {
        prepareLibrary();
        Path seasonDir = tempDir.resolve("lib").resolve("电视剧").resolve("国产剧").resolve("某剧 (2024)").resolve("Season 01");
        Files.writeString(seasonDir.resolve("占位.strm"), "x");

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(1);
        detail.setStatus("1");
        detail.setNewPath(seasonDir.toString());
        detail.setNewName("占位.strm");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));

        RenameOrphanPlus stale = new RenameOrphanPlus();
        stale.setId(77);
        stale.setDetailId(null);
        stale.setStatus("0");
        stale.setReason(OrphanReason.LOCAL_EXTRA);
        stale.setNewPath(seasonDir.toString());
        stale.setNewName("早就删掉了.strm");
        when(renameOrphanService.list()).thenReturn(List.of(stale));

        var summary = service.scan();

        verify(renameOrphanService).removeById(77);
        assertEquals(1, summary.resolved());
    }

    @Test
    void scan反向_已忽略的无主文件_不重复提醒也不被当成已恢复删掉() throws IOException {
        Path seasonDir = prepareLibrary();
        Files.writeString(seasonDir.resolve("无主.strm"), "x");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());

        RenameOrphanPlus ignored = new RenameOrphanPlus();
        ignored.setId(88);
        ignored.setDetailId(null);
        ignored.setStatus("2");
        ignored.setReason(OrphanReason.LOCAL_EXTRA);
        ignored.setNewPath(seasonDir.toString());
        ignored.setNewName("无主.strm");
        when(renameOrphanService.list()).thenReturn(List.of(ignored));

        var summary = service.scan();

        assertEquals(0, summary.localExtra());
        verify(renameOrphanService, never()).save(any());
        verify(renameOrphanService, never()).removeById(any());
    }

    @Test
    void scan反向_没有配置任何重命名任务_整个跳过() {
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());
        when(renameTaskService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(0, summary.localExtra());
        assertEquals(0, summary.emptyDir());
    }
}
