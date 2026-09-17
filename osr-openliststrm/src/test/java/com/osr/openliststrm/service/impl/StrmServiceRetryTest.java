package com.osr.openliststrm.service.impl;

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
import static org.mockito.Mockito.*;

/**
 * 按记录重试 STRM：直接走执行层，不再把记录预置成失败来骗过 existsStrm 去重。
 */
class StrmServiceRetryTest {

    @Mock
    private IOpenlistStrmPlusService openlistStrmPlusService;
    @Mock
    private StrmHelper strmHelper;

    private StrmServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        StrmServiceImpl target = new StrmServiceImpl();
        ReflectionTestUtils.setField(target, "openlistStrmPlusService", openlistStrmPlusService);
        ReflectionTestUtils.setField(target, "strmHelper", strmHelper);
        service = spy(target);
        // 真实生成要写磁盘、查任务覆盖，这里只关心重试有没有绕开去重直达执行层
        doNothing().when(service).generateOneFile(anyString());
    }

    private static OpenlistStrmPlus row(int id, String status) {
        OpenlistStrmPlus r = new OpenlistStrmPlus();
        r.setStrmId(id);
        r.setStrmPath("/网盘/剧集");
        r.setStrmFileName("a.mkv");
        r.setStrmStatus(status);
        return r;
    }

    @Test
    void 重试_不预置失败状态且绕过去重直接生成() {
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of(row(1, "0")));

        service.retryStrm(List.of("1"));

        verify(service).generateOneFile("/网盘/剧集/a.mkv");
        verify(strmHelper, never()).existsStrm(anyString(), anyString());
        verify(openlistStrmPlusService, never()).updateBatchById(any());
        verify(openlistStrmPlusService, never()).updateById(any(OpenlistStrmPlus.class));
    }

    @Test
    void 重试_已成功的记录同样重新生成() {
        // 典型场景：OpenList 地址或编码配置改了，要把旧 .strm 的内容刷新一遍
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of(row(1, "1")));

        service.retryStrm(List.of("1"));

        verify(service).generateOneFile("/网盘/剧集/a.mkv");
    }

    @Test
    void 按路径触发_已成功生成过的文件照旧去重跳过() {
        when(strmHelper.existsStrm("/网盘/剧集", "a.mkv")).thenReturn(true);

        service.strmOneFile("/网盘/剧集/a.mkv");

        verify(service, never()).generateOneFile(anyString());
    }

    @Test
    void 按路径触发_没有成功记录时进入执行层() {
        when(strmHelper.existsStrm("/网盘/剧集", "a.mkv")).thenReturn(false);

        service.strmOneFile("/网盘/剧集/a.mkv");

        verify(service).generateOneFile("/网盘/剧集/a.mkv");
    }
}
