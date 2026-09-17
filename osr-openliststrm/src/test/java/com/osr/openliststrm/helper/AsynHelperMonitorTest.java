package com.osr.openliststrm.helper;

import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.service.IStrmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 单文件复制监控：一次查询无响应不再判「未知」，任务消失（404）时先看目标文件再下结论，
 * 失败通知带上原因。
 */
class AsynHelperMonitorTest {

    private static final String DST_FILE = "/dst/剧集/a.mkv";

    private final OpenlistApi openlistApi = mock(OpenlistApi.class);
    private final IStrmService strmService = mock(IStrmService.class);
    private final CopyHelper copyHelper = mock(CopyHelper.class);
    private final IOpenlistCopyPlusService copyService = mock(IOpenlistCopyPlusService.class);
    private final OpenlistConfig config = mock(OpenlistConfig.class);
    private final CopyMonitorRegistry registry = mock(CopyMonitorRegistry.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    private MockedStatic<TgHelper> tg;
    private AsynHelper helper;

    @BeforeEach
    void setUp() {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            spring.when(() -> SpringUtils.getBean("virtualScheduledExecutor")).thenReturn(scheduler);
            helper = new AsynHelper();
        }
        ReflectionTestUtils.setField(helper, "openlistApi", openlistApi);
        ReflectionTestUtils.setField(helper, "strmService", strmService);
        ReflectionTestUtils.setField(helper, "copyHelper", copyHelper);
        ReflectionTestUtils.setField(helper, "openlistCopyPlusService", copyService);
        ReflectionTestUtils.setField(helper, "config", config);
        ReflectionTestUtils.setField(helper, "monitorRegistry", registry);
        when(config.getCopyMonitorMaxMinutes()).thenReturn(180L);
        when(config.getOpenListCopyStrm()).thenReturn("1");
        tg = mockStatic(TgHelper.class);
    }

    @AfterEach
    void tearDown() {
        tg.close();
    }

    private static OpenlistCopyPlus copy() {
        OpenlistCopyPlus copy = new OpenlistCopyPlus();
        copy.setCopyId(9);
        copy.setCopySrcPath("/src/剧集");
        copy.setCopyDstPath("/dst/剧集");
        copy.setCopySrcFileName("a.mkv");
        copy.setCopyDstFileName("a.mkv");
        copy.setCopyTaskId("task-1");
        copy.setCopyStatus("1");
        copy.setFileSize(2048L);
        return copy;
    }

    /** 跑一轮已调度的检查，返回本轮是否又调度了下一轮 */
    private boolean runScheduledRound() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).schedule(task.capture(), any(Instant.class));
        int before = mockingDetails(scheduler).getInvocations().size();
        task.getValue().run();
        return mockingDetails(scheduler).getInvocations().size() > before;
    }

    @Test
    void 查询无响应不立即判未知_连续多轮后停止监控且不改状态() {
        OpenlistCopyPlus copy = copy();
        when(openlistApi.copyInfo("task-1")).thenReturn(null);

        helper.isCopyDoneOneFile(DST_FILE, copy);
        for (int i = 1; i < AsynHelper.MAX_CONSECUTIVE_QUERY_FAILURES; i++) {
            assertTrue(runScheduledRound(), "第 " + i + " 次无响应后应继续监控");
        }
        // 最后一次：不再调度，状态留在处理中交给兜底任务
        assertEquals(false, runScheduledRound());
        verify(copyService, never()).updateById(any(OpenlistCopyPlus.class));
        assertEquals("1", copy.getCopyStatus());
    }

    @Test
    void 无响应之后恢复_计数清零() {
        OpenlistCopyPlus copy = copy();
        JSONObject running = JSONObject.of("code", 200, "data", JSONObject.of("state", 1));
        when(openlistApi.copyInfo("task-1")).thenReturn(null, null, null, null, running, null, null, null, null);

        helper.isCopyDoneOneFile(DST_FILE, copy);
        for (int i = 0; i < 9; i++) {
            assertTrue(runScheduledRound(), "第 " + (i + 1) + " 轮不应停止监控");
        }
    }

    @Test
    void 任务消失但目标文件已存在_记成功并生成STRM() {
        OpenlistCopyPlus copy = copy();
        copy.setFailReason("旧原因");
        when(openlistApi.copyInfo("task-1")).thenReturn(JSONObject.of("code", 404));
        when(openlistApi.getFile(DST_FILE)).thenReturn(JSONObject.of("code", 200, "data", JSONObject.of("size", 2048L)));

        helper.isCopyDoneOneFile(DST_FILE, copy);
        runScheduledRound();

        assertEquals("3", copy.getCopyStatus());
        assertNull(copy.getFailReason());
        verify(copyService).updateById(copy);
        verify(strmService).strmOneFile(DST_FILE, 2048L);
    }

    @Test
    void 任务消失且目标文件不存在_记未知并写明原因() {
        OpenlistCopyPlus copy = copy();
        when(openlistApi.copyInfo("task-1")).thenReturn(JSONObject.of("code", 404));
        when(openlistApi.getFile(DST_FILE)).thenReturn(JSONObject.of("code", 404, "message", "object not found"));

        helper.isCopyDoneOneFile(DST_FILE, copy);
        runScheduledRound();

        assertEquals("4", copy.getCopyStatus());
        assertEquals(CopyFailReason.taskLostAndDstMissing(), copy.getFailReason());
        verify(strmService, never()).strmOneFile(anyString(), any());
    }

    @Test
    void 任务消失且查目标文件也无响应_先不下结论继续监控() {
        OpenlistCopyPlus copy = copy();
        when(openlistApi.copyInfo("task-1")).thenReturn(JSONObject.of("code", 404));
        when(openlistApi.getFile(DST_FILE)).thenReturn(null);

        helper.isCopyDoneOneFile(DST_FILE, copy);

        assertTrue(runScheduledRound());
        verify(copyService, never()).updateById(any(OpenlistCopyPlus.class));
    }

    @Test
    void 复制失败_通知里带上原因() {
        OpenlistCopyPlus copy = copy();
        when(openlistApi.copyInfo("task-1")).thenReturn(
                JSONObject.of("code", 200, "data", JSONObject.of("state", 7, "error", "quota <exceeded>")));
        when(copyHelper.discardIfSourceGone(copy)).thenReturn(false);

        helper.isCopyDoneOneFile(DST_FILE, copy);
        runScheduledRound();

        assertEquals("2", copy.getCopyStatus());
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        tg.verify(() -> TgHelper.sendMsg(msg.capture()));
        // 原因来自 OpenList，要过 escapeHtml，否则 TG 的 HTML parse_mode 下整条消息发不出去
        assertTrue(msg.getValue().contains("原因：OpenList 复制任务失败：quota &lt;exceeded&gt;"), msg.getValue());
    }
}
