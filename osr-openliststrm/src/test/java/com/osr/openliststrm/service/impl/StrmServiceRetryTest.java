package com.osr.openliststrm.service.impl;

import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.helper.StrmHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 按记录重试 STRM：直接走执行层，不再把记录预置成失败来骗过 existsStrm 去重；
 * 并且按文件类型分流——字幕记录绝不能走 .strm 生成。
 */
class StrmServiceRetryTest {

    @Mock
    private IOpenlistStrmPlusService openlistStrmPlusService;
    @Mock
    private StrmHelper strmHelper;
    @Mock
    private OpenListHelper openListHelper;

    private StrmServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        StrmServiceImpl target = new StrmServiceImpl();
        ReflectionTestUtils.setField(target, "openlistStrmPlusService", openlistStrmPlusService);
        ReflectionTestUtils.setField(target, "strmHelper", strmHelper);
        ReflectionTestUtils.setField(target, "openListHelper", openListHelper);
        service = spy(target);
        // 真实生成要写磁盘、查任务覆盖、发网络请求，这里只关心走到了哪个执行分支
        doNothing().when(service).generateOneFile(anyString(), any());
        doNothing().when(service).downloadSubtitleOneFile(anyString());
        when(openListHelper.isVideo(anyString())).thenAnswer(inv -> inv.<String>getArgument(0).endsWith(".mkv"));
        when(openListHelper.isSrt(anyString())).thenAnswer(inv -> inv.<String>getArgument(0).endsWith(".srt"));
    }

    private static OpenlistStrmPlus row(int id, String name, String status) {
        OpenlistStrmPlus r = new OpenlistStrmPlus();
        r.setStrmId(id);
        r.setStrmPath("/网盘/剧集");
        r.setStrmFileName(name);
        r.setStrmStatus(status);
        return r;
    }

    @Test
    void 重试视频_不预置失败状态且绕过去重直接生成() {
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of(row(1, "a.mkv", "0")));

        service.retryStrm(List.of("1"));

        verify(service).generateOneFile("/网盘/剧集/a.mkv", null);
        verify(strmHelper, never()).existsStrm(anyString(), anyString());
        verify(openlistStrmPlusService, never()).updateBatchById(any());
        verify(openlistStrmPlusService, never()).updateById(any(OpenlistStrmPlus.class));
    }

    @Test
    void 重试已成功的视频_同样重新生成() {
        // 典型场景：OpenList 地址或编码配置改了，要把旧 .strm 的内容刷新一遍
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of(row(1, "a.mkv", "1")));

        service.retryStrm(List.of("1"));

        verify(service).generateOneFile("/网盘/剧集/a.mkv", null);
    }

    @Test
    void 重试字幕_重新下载字幕而绝不生成strm() {
        // 事故形态：a.srt 走 .strm 生成会写出 a.strm、内容指向字幕，覆盖同目录 a.mkv 的 .strm
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of(row(2, "a.srt", "0")));

        service.retryStrm(List.of("2"));

        verify(service).downloadSubtitleOneFile("/网盘/剧集/a.srt");
        verify(service, never()).generateOneFile(anyString(), any());
    }

    @Test
    void 重试既非视频也非字幕的记录_记一条说得清的失败() {
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of(row(3, "a.nfo", "0")));

        service.retryStrm(List.of("3"));

        verify(strmHelper).addStrm(eq("/网盘/剧集"), eq("a.nfo"), eq("0"), contains("既不是视频也不是字幕"), isNull());
        verify(service, never()).generateOneFile(anyString(), any());
        verify(service, never()).downloadSubtitleOneFile(anyString());
    }

    @Test
    void 执行层对非视频文件什么都不写() {
        // 按路径触发的入口（复制完成、TG 指令）也可能递进来字幕路径，执行层自己兜住
        doCallRealMethod().when(service).generateOneFile(anyString(), any());

        service.generateOneFile("/网盘/剧集/a.srt", null);

        verifyNoInteractions(strmHelper);
    }

    @Test
    void 按路径触发_已成功生成过的文件照旧去重跳过() {
        when(strmHelper.existsStrm("/网盘/剧集", "a.mkv")).thenReturn(true);

        service.strmOneFile("/网盘/剧集/a.mkv", 1024L);

        verify(service, never()).generateOneFile(anyString(), any());
    }

    @Test
    void 按路径触发_没有成功记录时带着大小进入执行层() {
        when(strmHelper.existsStrm("/网盘/剧集", "a.mkv")).thenReturn(false);

        service.strmOneFile("/网盘/剧集/a.mkv", 1024L);

        verify(service).generateOneFile("/网盘/剧集/a.mkv", 1024L);
    }
}
