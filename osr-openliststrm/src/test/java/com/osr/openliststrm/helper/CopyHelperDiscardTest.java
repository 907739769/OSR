package com.osr.openliststrm.helper;

import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复制失败时「源已消失」的判定。核心是把「AList 说找不到」与「AList 不可达」分开：
 * 前者删记录，后者必须原样记失败，否则一次网络抖动会把一批可重试的失败记录静默删光。
 *
 * @author Jack
 */
class CopyHelperDiscardTest {

    @Mock
    private IOpenlistCopyPlusService openlistCopyPlusService;

    @Mock
    private OpenlistApi openlistApi;

    @InjectMocks
    private CopyHelper helper;

    private OpenlistCopyPlus copy;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        copy = new OpenlistCopyPlus();
        copy.setCopyId(42);
        copy.setCopySrcPath("/download/osr/剧集/2026/Show.S01-ADWeb__kefDJG/Show.S01-ADWeb");
        copy.setCopySrcFileName("Show.S01E01.mkv");
    }

    private static JSONObject found() {
        JSONObject resp = new JSONObject();
        resp.put("code", 200);
        resp.put("data", new JSONObject());
        return resp;
    }

    private static JSONObject notFound() {
        JSONObject resp = new JSONObject();
        resp.put("code", 500);
        resp.put("message", "object not found");
        return resp;
    }

    @Test
    void 源文件已不存在_删记录并返回true() {
        when(openlistApi.getFile(eq(
                "/download/osr/剧集/2026/Show.S01-ADWeb__kefDJG/Show.S01-ADWeb/Show.S01E01.mkv")))
                .thenReturn(notFound());

        assertTrue(helper.discardIfSourceGone(copy));
        verify(openlistCopyPlusService).removeById(42);
    }

    @Test
    void 源文件还在_不动记录并返回false() {
        when(openlistApi.getFile(any())).thenReturn(found());

        assertFalse(helper.discardIfSourceGone(copy));
        verify(openlistCopyPlusService, never()).removeById(any());
    }

    @Test
    void AList不可达_不下结论_按普通失败处理() {
        // 响应为 null = 网络层失败，与「AList 明确说找不到」语义完全不同
        when(openlistApi.getFile(any())).thenReturn(null);

        assertFalse(helper.discardIfSourceGone(copy));
        verify(openlistCopyPlusService, never()).removeById(any());
    }

    @Test
    void 探测抛异常_按普通失败处理() {
        when(openlistApi.getFile(any())).thenThrow(new RuntimeException("boom"));

        assertFalse(helper.discardIfSourceGone(copy));
        verify(openlistCopyPlusService, never()).removeById(any());
    }

    @Test
    void 记录缺源路径或文件名_不探测也不删() {
        OpenlistCopyPlus broken = new OpenlistCopyPlus();
        broken.setCopyId(7);

        assertFalse(helper.discardIfSourceGone(broken));
        verify(openlistApi, never()).getFile(any());
        verify(openlistCopyPlusService, never()).removeById(any());
    }
}
