package com.osr.openliststrm.orphan;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameOrphanPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameOrphanPlusService;
import com.osr.openliststrm.scrape.ScrapeService;
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
    private ScrapeService scrapeService;

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
        service.scrapeService = scrapeService;
    }

    @Test
    void clean_原因是网盘源丢失_删除本地文件并清理刮削产物和明细记录() {
        RenameOrphanPlus orphan = new RenameOrphanPlus();
        orphan.setId(1);
        orphan.setDetailId(42);
        orphan.setReason("source_missing");
        when(renameOrphanService.listByIds(List.of(1))).thenReturn(List.of(orphan));

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(42);
        detail.setNewPath("/nonexistent/dir/for/test");
        detail.setNewName("does-not-exist.strm");
        when(renameDetailService.getById(42)).thenReturn(detail);

        service.clean(List.of(1));

        verify(scrapeService).deleteScrapeFiles(detail);
        verify(renameDetailService).removeById(42);
        verify(renameOrphanService).updateBatchById(argThat(list -> {
            RenameOrphanPlus updated = list.iterator().next();
            return "1".equals(updated.getStatus()) && updated.getCleanTime() != null;
        }));
    }

    @Test
    void clean_原因是本地文件已丢失_不重复删除本地文件仅清理刮削产物和明细记录() {
        RenameOrphanPlus orphan = new RenameOrphanPlus();
        orphan.setId(2);
        orphan.setDetailId(43);
        orphan.setReason("local_missing");
        when(renameOrphanService.listByIds(List.of(2))).thenReturn(List.of(orphan));

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(43);
        detail.setNewPath("/data/media/whatever");
        detail.setNewName("whatever.strm");
        when(renameDetailService.getById(43)).thenReturn(detail);

        service.clean(List.of(2));

        verify(scrapeService).deleteScrapeFiles(detail);
        verify(renameDetailService).removeById(43);
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

    @Test
    void scan_本地文件不存在_判定为local_missing并插入孤儿记录() throws IOException {
        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(1);
        detail.setStatus("1");
        detail.setNewPath(tempDir.resolve("missing-dir").toString());
        detail.setNewName("ghost.strm");
        detail.setTitle("Ghost");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));
        when(renameOrphanService.list()).thenReturn(List.of());

        service.scan();

        verify(renameOrphanService).save(argThat(o -> "local_missing".equals(o.getReason()) && o.getDetailId() == 1));
    }

    @Test
    void scan_本地文件不存在但该detail已有已忽略的孤儿记录_跳过不落库() throws IOException {
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
        ignored.setReason("local_missing");
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

        verify(renameOrphanService).save(argThat(o -> "source_missing".equals(o.getReason()) && o.getDetailId() == 2));
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
    void scan_本地文件和网盘源都存在_不产生孤儿记录() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("movies2"));
        Path strmFile = dir.resolve("b.strm");
        when(config.getOpenListUrl()).thenReturn("http://alist.local");
        when(config.getOpenListStrmEncode()).thenReturn("0");
        Files.writeString(strmFile, "http://alist.local/d/movies2/b.mkv", StandardCharsets.UTF_8);

        RenameDetailPlus detail = new RenameDetailPlus();
        detail.setId(3);
        detail.setStatus("1");
        detail.setNewPath(dir.toString());
        detail.setNewName("b.strm");
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(detail));
        when(renameOrphanService.list()).thenReturn(List.of());
        when(config.getTraversalConcurrency()).thenReturn(4);

        JSONObject dirListing = new JSONObject();
        dirListing.put("code", 200);
        JSONObject data = new JSONObject();
        com.alibaba.fastjson2.JSONArray content = new com.alibaba.fastjson2.JSONArray();
        JSONObject file = new JSONObject();
        file.put("name", "b.mkv");
        file.put("is_dir", false);
        content.add(file);
        data.put("content", content);
        dirListing.put("data", data);
        when(openListApi.getOpenlist(eq("/movies2"), eq(false))).thenReturn(dirListing);

        service.scan();

        verify(renameOrphanService, never()).save(any());
        verify(renameOrphanService, never()).updateBatchById(any());
    }
}
