package com.osr.openliststrm.rename.cleanup;

import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.scrape.ScrapeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RenameCleanupServiceTest {

    @Mock
    private ScrapeService scrapeService;
    @Mock
    private IRenameDetailPlusService renameDetailService;

    private RenameCleanupService service;

    @TempDir
    Path tempDir;

    /** 目标库骨架：<temp>/电视剧/国产剧/某剧 (2024)/Season 01 */
    private Path seasonDir;
    private Path showRoot;
    private Path mediaRoot;

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);
        service = new RenameCleanupService();
        inject("scrapeService", scrapeService);
        inject("renameDetailService", renameDetailService);

        mediaRoot = tempDir.resolve("电视剧");
        showRoot = mediaRoot.resolve("国产剧").resolve("某剧 (2024)");
        seasonDir = showRoot.resolve("Season 01");
        Files.createDirectories(seasonDir);
    }

    private void inject(String field, Object value) {
        try {
            var f = RenameCleanupService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private RenameDetailPlus detail(int id, Path dir, String name) {
        RenameDetailPlus d = new RenameDetailPlus();
        d.setId(id);
        d.setNewPath(dir.toString());
        d.setNewName(name);
        d.setMediaType("tv");
        return d;
    }

    @Test
    void 回收空目录_逐级向上删到电影电视剧那一层为止() throws IOException {
        int removed = service.reclaimEmptyDirs(seasonDir);

        assertEquals(3, removed, "Season 01 / 某剧 (2024) / 国产剧 三层都该被回收");
        assertFalse(Files.exists(showRoot));
        assertTrue(Files.exists(mediaRoot), "电视剧 这一层是媒体库骨架，绝不能删");
    }

    @Test
    void 回收空目录_遇到非空目录立即停手() throws IOException {
        Files.writeString(showRoot.resolve("tvshow.nfo"), "x");

        int removed = service.reclaimEmptyDirs(seasonDir);

        assertEquals(1, removed);
        assertFalse(Files.exists(seasonDir));
        assertTrue(Files.exists(showRoot), "剧集根目录还有 tvshow.nfo，不该被删");
    }

    @Test
    void 回收空目录_找不到电影电视剧锚点时一个都不删() throws IOException {
        Path stray = tempDir.resolve("somewhere").resolve("deep");
        Files.createDirectories(stray);

        assertEquals(0, service.reclaimEmptyDirs(stray));
        assertTrue(Files.exists(stray), "边界不明时宁可不动，不能自行猜一个上界往上删");
    }

    @Test
    void 清理产物_先删文件后删记录且整批id一次性传给兄弟判定() throws IOException {
        Path a = seasonDir.resolve("a.strm");
        Path b = seasonDir.resolve("b.strm");
        Files.writeString(a, "x");
        Files.writeString(b, "x");
        RenameDetailPlus d1 = detail(1, seasonDir, "a.strm");
        RenameDetailPlus d2 = detail(2, seasonDir, "b.strm");
        when(renameDetailService.removeByIds(any())).thenReturn(true);

        RenameCleanupService.PurgeResult result = service.purge(List.of(d1, d2), true);

        assertEquals(2, result.mainFiles());
        assertEquals(2, result.records());
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));

        ArgumentCaptor<ScrapeService.DeleteOptions> opts = ArgumentCaptor.forClass(ScrapeService.DeleteOptions.class);
        verify(scrapeService, org.mockito.Mockito.times(2)).deleteScrapeFiles(any(), opts.capture());
        assertEquals(java.util.Set.of(1, 2), java.util.Set.copyOf(opts.getValue().excludeDetailIds()),
                "兄弟判定必须排除整批，否则前几条会误以为还有兄弟而跳过共享元数据");
    }

    @Test
    void 清理产物_不删记录时数据库行保留() throws IOException {
        Files.writeString(seasonDir.resolve("a.strm"), "x");

        RenameCleanupService.PurgeResult result = service.purge(List.of(detail(1, seasonDir, "a.strm")), false);

        assertEquals(1, result.mainFiles());
        assertEquals(0, result.records());
        verify(renameDetailService, never()).removeByIds(any());
    }

    @Test
    void 重命名换位_新旧同剧时保留剧集根目录共享文件() throws IOException {
        Path oldFile = seasonDir.resolve("旧名.strm");
        Files.writeString(oldFile, "x");
        Path newDest = showRoot.resolve("Season 02").resolve("新名.strm");
        Files.createDirectories(newDest.getParent());
        RenameDetailPlus record = detail(1, seasonDir, "旧名.strm");

        service.purgeRelocated(record, newDest);

        assertFalse(Files.exists(oldFile), "旧主文件必须删掉");
        ArgumentCaptor<ScrapeService.DeleteOptions> opts = ArgumentCaptor.forClass(ScrapeService.DeleteOptions.class);
        verify(scrapeService).deleteScrapeFiles(any(), opts.capture());
        assertEquals(showRoot.toAbsolutePath().normalize(), opts.getValue().keepShowRoot(),
                "新位置还在同一部剧下，tvshow.nfo 与剧集图片必须保留");
    }

    @Test
    void 重命名换位_目标与原位置相同时什么都不做() throws IOException {
        Path file = seasonDir.resolve("同名.strm");
        Files.writeString(file, "x");

        service.purgeRelocated(detail(1, seasonDir, "同名.strm"), file);

        assertTrue(Files.exists(file));
        verify(scrapeService, never()).deleteScrapeFiles(any(), any());
    }

    @Test
    void 清理无主文件_删文件与同名nfo并回收空目录() throws IOException {
        Path file = seasonDir.resolve("无主.strm");
        Path nfo = seasonDir.resolve("无主.nfo");
        Files.writeString(file, "x");
        Files.writeString(nfo, "x");

        RenameCleanupService.PurgeResult result = service.purgeExtraFile(file);

        assertEquals(1, result.mainFiles());
        assertEquals(1, result.scrapeFiles());
        assertEquals(3, result.dirs());
        assertFalse(Files.exists(seasonDir));
    }

    @Test
    void 清理仅元数据目录_只删白名单内的元数据并回收目录() throws IOException {
        Files.writeString(seasonDir.resolve("x.nfo"), "x");
        Files.writeString(seasonDir.resolve("season-poster.jpg"), "x");

        RenameCleanupService.PurgeResult result = service.purgeMetadataOnlyDir(seasonDir);

        assertEquals(2, result.scrapeFiles());
        assertFalse(Files.exists(seasonDir));
    }

    @Test
    void 清理仅元数据目录_出现白名单外的文件时整个跳过() throws IOException {
        Files.writeString(seasonDir.resolve("x.nfo"), "x");
        Path stranger = seasonDir.resolve("用户的备注.txt");
        Files.writeString(stranger, "x");

        RenameCleanupService.PurgeResult result = service.purgeMetadataOnlyDir(seasonDir);

        assertEquals(RenameCleanupService.PurgeResult.EMPTY, result);
        assertTrue(Files.exists(stranger), "目录里有不认识的东西就整个不动");
        assertTrue(Files.exists(seasonDir.resolve("x.nfo")));
    }

    @Test
    void 预览_只列出磁盘上真实存在的文件且去重() throws IOException {
        Path a = seasonDir.resolve("a.strm");
        Files.writeString(a, "x");
        Path sharedNfo = showRoot.resolve("tvshow.nfo");
        Files.writeString(sharedNfo, "x");
        Path missing = seasonDir.resolve("season.nfo");
        // 两条记录都解析出同一个 tvshow.nfo，预览里只该出现一次
        when(scrapeService.resolveScrapeFiles(any(), any())).thenReturn(List.of(sharedNfo, missing));

        List<String> preview = service.preview(List.of(detail(1, seasonDir, "a.strm"), detail(2, seasonDir, "b.strm")));

        assertEquals(List.of(a.toAbsolutePath().normalize().toString(), sharedNfo.toString()), preview);
    }
}
