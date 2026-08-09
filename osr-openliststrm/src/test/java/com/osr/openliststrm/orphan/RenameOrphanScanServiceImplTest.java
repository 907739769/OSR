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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /**
     * 建出 &lt;temp&gt;/lib/电视剧/国产剧/某剧 (2024)/Season 01 并把 lib 配成任务目标目录。
     * 不设 create_time，即基线为 0——反向扫描退化为全量判定，与加基线之前的行为一致。
     */
    private Path prepareLibrary() throws IOException {
        return prepareLibrary(null);
    }

    /** 同上，但给任务设一个创建时间作为反向扫描的基线（格式同 MyMetaObjectHandler 写入的那种） */
    private Path prepareLibrary(String taskCreateTime) throws IOException {
        Path lib = tempDir.resolve("lib");
        Path seasonDir = lib.resolve("电视剧").resolve("国产剧").resolve("某剧 (2024)").resolve("Season 01");
        Files.createDirectories(seasonDir);
        RenameTaskPlus task = new RenameTaskPlus();
        task.setId(1);
        task.setTargetRoot(lib.toString());
        task.setCreateTime(taskCreateTime);
        when(renameTaskService.list()).thenReturn(List.of(task));
        return seasonDir;
    }

    /** 把文件的最后修改时间设成相对现在的偏移量（负数=过去） */
    private static void setModified(Path path, Duration offsetFromNow) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().plus(offsetFromNow)));
    }

    /** 相对现在偏移若干天的时刻，按 create_time 的存储格式（yyyy-MM-dd HH:mm:ss）输出 */
    private static String daysAgo(long days) {
        return LocalDateTime.now().minusDays(days).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
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

    // ------------------------------------------------------------------
    // 反向扫描：mtime 基线
    // ------------------------------------------------------------------

    @Test
    void scan反向_文件早于任务创建时间_不判为无主() throws IOException {
        Path seasonDir = prepareLibrary(daysAgo(30));
        Path old = seasonDir.resolve("重命名接管之前就在的.strm");
        Files.writeString(old, "x");
        setModified(old, Duration.ofDays(-365));
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(0, summary.localExtra(), "任务建起来之前就躺在库里的历史文件不归它管");
        assertEquals(1, summary.baselineSkipped());
        verify(renameOrphanService, never()).save(any());
    }

    @Test
    void scan反向_文件晚于任务创建时间_照常判为无主() throws IOException {
        Path seasonDir = prepareLibrary(daysAgo(30));
        Path fresh = seasonDir.resolve("只删了记录文件还在.strm");
        Files.writeString(fresh, "x");
        setModified(fresh, Duration.ofDays(-1));
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(1, summary.localExtra(), "基线之后产生的无主文件正是这个方向要抓的");
        assertEquals(0, summary.baselineSkipped());
    }

    @Test
    void scan反向_被基线放过的媒体文件仍算目录里有主文件_不把目录判成仅剩元数据() throws IOException {
        Path seasonDir = prepareLibrary(daysAgo(30));
        Path oldMedia = seasonDir.resolve("历史影片.mkv");
        Files.writeString(oldMedia, "x");
        setModified(oldMedia, Duration.ofDays(-365));
        Path nfo = seasonDir.resolve("历史影片.nfo");
        Files.writeString(nfo, "x");
        setModified(nfo, Duration.ofDays(-365));
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(0, summary.localExtra());
        assertEquals(0, summary.metadataOnly(),
                "基线只影响报不报，不影响'这个目录有没有主媒体文件'的事实——判错会让目录被清理掉");
        assertEquals(0, summary.emptyDir());
    }

    @Test
    void scan反向_任务没有创建时间_退化为全量判定() throws IOException {
        Path seasonDir = prepareLibrary(null);
        Path old = seasonDir.resolve("很老的.strm");
        Files.writeString(old, "x");
        setModified(old, Duration.ofDays(-3650));
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(1, summary.localExtra(), "没有基线可用时宁可多报也不漏报");
        assertEquals(0, summary.baselineSkipped());
    }

    @Test
    void scan反向_同一锚点被两个任务共用_取最早的创建时间做基线() throws IOException {
        Path lib = tempDir.resolve("lib");
        Path seasonDir = lib.resolve("电视剧").resolve("国产剧").resolve("某剧 (2024)").resolve("Season 01");
        Files.createDirectories(seasonDir);
        Path file = seasonDir.resolve("早建任务的产物.strm");
        Files.writeString(file, "x");
        setModified(file, Duration.ofDays(-100));

        RenameTaskPlus early = new RenameTaskPlus();
        early.setId(1);
        early.setTargetRoot(lib.toString());
        early.setCreateTime(daysAgo(200));
        RenameTaskPlus late = new RenameTaskPlus();
        late.setId(2);
        late.setTargetRoot(lib.toString());
        late.setCreateTime(daysAgo(10));
        when(renameTaskService.list()).thenReturn(List.of(early, late));
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(1, summary.localExtra(), "晚建的任务不该把早建任务的产物挡在基线之外");
    }

    @Test
    void scan反向_目录级发现有独立额度_不被文件级发现挤掉() throws IOException {
        // 一个空目录 + 一个无主文件同时存在时，两类发现各走各的额度，都要落库
        Path seasonDir = prepareLibrary();
        Files.writeString(seasonDir.resolve("无主.strm"), "x");
        Path emptyDir = tempDir.resolve("lib").resolve("电视剧").resolve("国产剧").resolve("空剧 (2024)");
        Files.createDirectories(emptyDir);
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of());
        when(renameOrphanService.list()).thenReturn(List.of());

        var summary = service.scan();

        assertEquals(1, summary.localExtra());
        assertEquals(1, summary.emptyDir());
        assertEquals(0, summary.truncated());
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
