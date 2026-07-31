package com.osr.openliststrm.mybatisplus.service.impl;

import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyTaskPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * validateSavePath 是本任务唯一有真实逻辑的方法（路径包含关系判断 + 启用状态过滤），
 * 因此单独补充单元测试覆盖其分支。
 *
 * @author Jack
 */
class PtDownloaderPlusServiceImplTest {

    @Mock
    private IOpenlistCopyTaskPlusService copyTaskService;

    @InjectMocks
    private PtDownloaderPlusServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private OpenlistCopyTaskPlus task(String monitorDir, String status) {
        OpenlistCopyTaskPlus task = new OpenlistCopyTaskPlus();
        task.setMonitorDir(monitorDir);
        task.setCopyTaskStatus(status);
        return task;
    }

    @Test
    void validateSavePath_保存路径为空_返回提示不为空() {
        assertEquals("保存路径不能为空", service.validateSavePath(""));
        assertEquals("保存路径不能为空", service.validateSavePath(null));
    }

    @Test
    void validateSavePath_路径位于已启用任务监听目录之下_返回null() {
        when(copyTaskService.list()).thenReturn(List.of(task("media/downloads", "1")));

        String result = service.validateSavePath("media/downloads/movie");

        assertNull(result);
    }

    @Test
    void validateSavePath_路径仅位于已停用任务监听目录之下_返回提示() {
        when(copyTaskService.list()).thenReturn(List.of(task("media/downloads", "0")));

        String result = service.validateSavePath("media/downloads/movie");

        assertEquals("保存路径不在任何已启用文件同步任务的监听目录之下，下载完成后不会被自动上传", result);
    }

    @Test
    void validateSavePath_没有任何同步任务_返回提示() {
        when(copyTaskService.list()).thenReturn(List.of());

        String result = service.validateSavePath("media/downloads/movie");

        assertEquals("保存路径不在任何已启用文件同步任务的监听目录之下，下载完成后不会被自动上传", result);
    }

    @Test
    void validateSavePath_监听目录为空字符串的任务_被跳过不抛异常() {
        when(copyTaskService.list()).thenReturn(List.of(task("", "1")));

        String result = service.validateSavePath("media/downloads/movie");

        assertEquals("保存路径不在任何已启用文件同步任务的监听目录之下，下载完成后不会被自动上传", result);
    }

    @Test
    void validateSavePath_路径不在任何监听目录之下_返回提示() {
        when(copyTaskService.list()).thenReturn(List.of(task("media/other", "1")));

        String result = service.validateSavePath("media/downloads/movie");

        assertEquals("保存路径不在任何已启用文件同步任务的监听目录之下，下载完成后不会被自动上传", result);
    }

    @Test
    void validateSavePath_保存路径是非法路径字符串_返回提示不抛异常() {
        // \u0000 (NUL) 在 Windows 与 Linux 上都会让 Paths.get 抛出 InvalidPathException
        String result = service.validateSavePath("media\u0000downloads");

        assertEquals("保存路径格式不合法，请检查路径中是否包含非法字符", result);
    }

    @Test
    void validateSavePath_某任务monitorDir非法_跳过该任务后续合法任务仍能匹配() {
        when(copyTaskService.list()).thenReturn(List.of(
                task("media\u0000broken", "1"),
                task("media/downloads", "1")
        ));

        String result = service.validateSavePath("media/downloads/movie");

        assertNull(result);
    }
}
