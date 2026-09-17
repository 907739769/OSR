package com.osr.openliststrm.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.AsynHelper;
import com.osr.openliststrm.helper.CopyHelper;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 按记录重试复制：不再靠「先改成失败骗过去重」，而是绕开去重直接走执行层，并用条件更新认领。
 */
class CopyServiceRetryTest {

    private static final String SRC_DIR = "/src/剧集";
    private static final String DST_DIR = "/dst/剧集";
    private static final String NAME = "a.mkv";

    @Mock
    private IOpenlistCopyPlusService openlistCopyPlusService;
    @Mock
    private OpenlistApi openlistApi;
    @Mock
    private CopyHelper copyHelper;
    @Mock
    private AsynHelper asynHelper;
    @Mock
    private OpenlistConfig config;
    @Mock
    private OpenListHelper openListHelper;

    @InjectMocks
    private CopyServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(config.getMinFileSizeBytes()).thenReturn(100L);
        when(openListHelper.isVideo(anyString())).thenReturn(true);
        // 目标默认不存在、源默认存在且体积够、提交默认成功
        when(openlistApi.getFile(DST_DIR + "/" + NAME)).thenReturn(JSONObject.of("code", 404, "message", "object not found"));
        when(openlistApi.getFile(SRC_DIR + "/" + NAME)).thenReturn(JSONObject.of("code", 200, "data", JSONObject.of("size", 1000L)));
        when(openlistApi.copyOpenlist(anyString(), anyString(), anyList())).thenReturn(JSONObject.of("code", 200,
                "data", JSONObject.of("tasks", List.of(JSONObject.of("id", "task-1")))));
        when(openlistCopyPlusService.update(any(OpenlistCopyPlus.class), any(Wrapper.class))).thenReturn(true);
    }

    private static OpenlistCopyPlus record(int id, String status) {
        OpenlistCopyPlus copy = new OpenlistCopyPlus();
        copy.setFailReason("2".equals(status) || "4".equals(status) ? "上一次失败的原因" : null);
        copy.setCopyId(id);
        copy.setCopySrcPath(SRC_DIR);
        copy.setCopyDstPath(DST_DIR);
        copy.setCopySrcFileName(NAME);
        copy.setCopyDstFileName(NAME);
        copy.setCopyStatus(status);
        copy.setCopyTaskId("old-task");
        return copy;
    }

    @Test
    void 重试成功提交_按id写回同一行且不经过去重也不新增记录() {
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(record(5, "2")));

        int submitted = service.retryCopy(List.of("5"));

        assertEquals(1, submitted);
        verify(copyHelper, never()).existsCopy(any());
        verify(copyHelper, never()).addCopy(any());
        ArgumentCaptor<OpenlistCopyPlus> saved = ArgumentCaptor.forClass(OpenlistCopyPlus.class);
        verify(openlistCopyPlusService).updateById(saved.capture());
        assertEquals(5, saved.getValue().getCopyId());
        assertEquals("1", saved.getValue().getCopyStatus());
        assertEquals("task-1", saved.getValue().getCopyTaskId());
        // 上一次的失败原因必须清掉：fail_reason 是 ALWAYS 策略，对象上留着就会被原样写回
        assertNull(saved.getValue().getFailReason());
        assertEquals(1000L, saved.getValue().getFileSize());
        verify(asynHelper).isCopyDoneOneFile(eq(DST_DIR + "/" + NAME), same(saved.getValue()));
    }

    @Test
    void 认领是条件更新_只从失败或未知改成处理中() {
        OpenlistCopyPlus copy = record(5, "4");

        service.claimForRetry(copy);

        ArgumentCaptor<OpenlistCopyPlus> set = ArgumentCaptor.forClass(OpenlistCopyPlus.class);
        ArgumentCaptor<Wrapper> where = ArgumentCaptor.forClass(Wrapper.class);
        verify(openlistCopyPlusService).update(set.capture(), where.capture());
        assertEquals("1", set.getValue().getCopyStatus());
        assertEquals("", set.getValue().getCopyTaskId());
        String sql = where.getValue().getSqlSegment();
        assertEquals(true, sql.contains("copy_id") && sql.contains("copy_status IN"), sql);
        assertEquals("1", copy.getCopyStatus());
    }

    @Test
    void 并发重试同一条记录_只有认领成功的一方提交复制任务() {
        // 两次重试各自查到的都是失败状态；库里的条件更新只会有一次影响到行
        when(openlistCopyPlusService.listByIds(any())).thenAnswer(inv -> List.of(record(5, "2")));
        when(openlistCopyPlusService.update(any(OpenlistCopyPlus.class), any(Wrapper.class))).thenReturn(true, false);

        service.retryCopy(List.of("5"));
        service.retryCopy(List.of("5"));

        verify(openlistApi, times(1)).copyOpenlist(anyString(), anyString(), anyList());
        verify(openlistCopyPlusService, times(1)).updateById(any(OpenlistCopyPlus.class));
    }

    @Test
    void 处理中与已成功的记录不重试() {
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(record(5, "1"), record(6, "3")));

        int submitted = service.retryCopy(List.of("5", "6"));

        assertEquals(0, submitted);
        verify(openlistCopyPlusService, never()).update(any(OpenlistCopyPlus.class), any(Wrapper.class));
        verifyNoInteractions(openlistApi);
    }

    @Test
    void 重试时源文件已不存在_删除记录而不是留在失败() {
        when(openlistApi.getFile(SRC_DIR + "/" + NAME)).thenReturn(JSONObject.of("code", 404, "message", "object not found"));
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(record(5, "2")));

        service.retryCopy(List.of("5"));

        verify(openlistCopyPlusService).removeById(5);
        verify(openlistApi, never()).copyOpenlist(anyString(), anyString(), anyList());
        verify(openlistCopyPlusService, never()).updateById(any(OpenlistCopyPlus.class));
    }

    @Test
    void 重试时OpenList无响应_退回失败而不删记录() {
        // 判不出源在不在：按源已消失删掉的话，一次网络抖动就会把可重试的记录删光
        when(openlistApi.getFile(SRC_DIR + "/" + NAME)).thenReturn(null);
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(record(5, "2")));

        service.retryCopy(List.of("5"));

        assertRevertedToFailed("查询源文件失败（OpenList 无响应）");
        verify(openlistCopyPlusService, never()).removeById(any());
    }

    @Test
    void 重试时体积低于阈值_退回失败() {
        when(openlistApi.getFile(SRC_DIR + "/" + NAME)).thenReturn(JSONObject.of("code", 200, "data", JSONObject.of("size", 10L)));
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(record(5, "2")));

        service.retryCopy(List.of("5"));

        OpenlistCopyPlus saved = assertRevertedToFailed("源文件大小 10 字节，低于同步阈值 100 字节，未复制");
        assertEquals(10L, saved.getFileSize());
        verify(openlistApi, never()).copyOpenlist(anyString(), anyString(), anyList());
    }

    @Test
    void 重试时提交复制失败_退回失败() {
        when(openlistApi.copyOpenlist(anyString(), anyString(), anyList())).thenReturn(null);
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(record(5, "2")));

        service.retryCopy(List.of("5"));

        assertRevertedToFailed("提交复制任务失败（OpenList 无响应）");
        verify(asynHelper, never()).isCopyDoneOneFile(anyString(), any());
    }

    @Test
    void 重试时目标已存在_直接记成功() {
        when(openlistApi.getFile(DST_DIR + "/" + NAME)).thenReturn(JSONObject.of("code", 200, "data", JSONObject.of("size", 1000L)));
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(record(5, "2")));

        service.retryCopy(List.of("5"));

        ArgumentCaptor<OpenlistCopyPlus> saved = ArgumentCaptor.forClass(OpenlistCopyPlus.class);
        verify(openlistCopyPlusService).updateById(saved.capture());
        assertEquals("3", saved.getValue().getCopyStatus());
        assertNull(saved.getValue().getFailReason());
        assertEquals(1000L, saved.getValue().getFileSize());
        verify(openlistApi, never()).copyOpenlist(anyString(), anyString(), anyList());
    }

    @Test
    void 外部事件入口_已处理过的文件照旧去重跳过() {
        when(copyHelper.existsCopy(any())).thenReturn(true);

        service.syncOneFile("/src", "/dst", "剧集/" + NAME);

        verifyNoInteractions(openlistApi);
    }

    @Test
    void 外部事件入口_源文件不存在时不留记录() {
        when(openlistApi.getFile(SRC_DIR + "/" + NAME)).thenReturn(JSONObject.of("code", 404));

        service.syncOneFile("/src", "/dst", "剧集/" + NAME);

        verify(copyHelper, never()).addCopy(any());
        verify(openlistCopyPlusService, never()).updateById(any(OpenlistCopyPlus.class));
        verify(openlistCopyPlusService, never()).removeById(any());
    }

    @Test
    void 外部事件入口_新文件提交成功后走原来的异步落库() {
        service.syncOneFile("/src", "/dst", "剧集/" + NAME);

        ArgumentCaptor<OpenlistCopyPlus> added = ArgumentCaptor.forClass(OpenlistCopyPlus.class);
        verify(copyHelper).addCopy(added.capture());
        assertEquals("1", added.getValue().getCopyStatus());
        assertEquals(SRC_DIR, added.getValue().getCopySrcPath());
        verify(openlistCopyPlusService, never()).updateById(any(OpenlistCopyPlus.class));
    }

    private OpenlistCopyPlus assertRevertedToFailed(String reason) {
        ArgumentCaptor<OpenlistCopyPlus> saved = ArgumentCaptor.forClass(OpenlistCopyPlus.class);
        verify(openlistCopyPlusService).updateById(saved.capture());
        assertEquals(5, saved.getValue().getCopyId());
        assertEquals("2", saved.getValue().getCopyStatus());
        assertEquals(reason, saved.getValue().getFailReason());
        return saved.getValue();
    }
}
