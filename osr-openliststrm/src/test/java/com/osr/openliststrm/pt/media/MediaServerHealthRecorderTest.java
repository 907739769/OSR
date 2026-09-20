package com.osr.openliststrm.pt.media;

import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 连通状态写回的节流。
 *
 * <p>这组用例守的是两个方向相反的失败：写太勤（对账每 10 分钟跑一遍<b>全部</b>订阅，而记录发生在
 * 每条订阅的查询处，实测 103 条订阅就是每 10 分钟 103 次 UPDATE），以及写太懒（「刚刚坏掉」
 * 「刚刚恢复」这两个唯一有信息量的时刻落不进库，页面上看到的状态永远慢一拍）。
 *
 * @author Jack
 */
class MediaServerHealthRecorderTest {

    private static final long T0 = 1_700_000_000_000L;

    private IPtMediaServerPlusService service;
    private MediaServerHealthRecorder recorder;

    @BeforeEach
    void setUp() {
        service = mock(IPtMediaServerPlusService.class);
        recorder = new MediaServerHealthRecorder(service);
    }

    @Test
    void 首次记录必定落库() {
        recorder.record(1, true, null, T0);

        verify(service).updateProbeResult(1, true, null);
    }

    @Test
    void 结果没变化且未到间隔时不重复落库() {
        recorder.record(1, true, null, T0);
        recorder.record(1, true, null, T0 + 1000);
        recorder.record(1, true, null, T0 + 60_000);

        verify(service, times(1)).updateProbeResult(anyInt(), anyBoolean(), isNull());
    }

    @Test
    void 由通转不通立刻落库() {
        recorder.record(1, true, null, T0);
        Mockito.clearInvocations(service);

        recorder.record(1, false, "connection refused", T0 + 1000);

        verify(service).updateProbeResult(1, false, "connection refused");
    }

    @Test
    void 由不通转通立刻落库() {
        recorder.record(1, false, "connection refused", T0);
        Mockito.clearInvocations(service);

        recorder.record(1, true, null, T0 + 1000);

        verify(service).updateProbeResult(1, true, null);
    }

    /** 同样是失败，但换了一种失败——401 与超时的处置方向完全不同，不能被节流吞掉 */
    @Test
    void 失败原因变了也要落库() {
        recorder.record(1, false, "connection refused", T0);
        Mockito.clearInvocations(service);

        recorder.record(1, false, "媒体服务器返回 HTTP 401", T0 + 1000);

        verify(service).updateProbeResult(1, false, "媒体服务器返回 HTTP 401");
    }

    /** 状态一直没变也要按间隔刷新时间，否则页面上那个「上次连通」会一直停在很久以前 */
    @Test
    void 超过间隔后刷新一次时间() {
        recorder.record(1, true, null, T0);
        Mockito.clearInvocations(service);

        recorder.record(1, true, null, T0 + MediaServerHealthRecorder.PERSIST_INTERVAL_MILLIS - 1);
        verify(service, never()).updateProbeResult(anyInt(), anyBoolean(), anyString());
        verify(service, never()).updateProbeResult(anyInt(), anyBoolean(), isNull());

        recorder.record(1, true, null, T0 + MediaServerHealthRecorder.PERSIST_INTERVAL_MILLIS);
        verify(service).updateProbeResult(1, true, null);
    }

    /** 节流按服务器各算各的，一台的写入不能把另一台的挡掉 */
    @Test
    void 多台服务器各自节流() {
        recorder.record(1, true, null, T0);
        recorder.record(2, true, null, T0);

        verify(service).updateProbeResult(1, true, null);
        verify(service).updateProbeResult(2, true, null);
    }

    /**
     * 落库失败要把记忆丢掉让下一次重试，而不是让这台服务器的状态卡住一个完整的间隔。
     * 同时：写不进去绝不能让对账本身出错，异常必须被吞掉。
     */
    @Test
    void 落库失败后下一次立即重试而不是等满间隔() {
        doThrow(new RuntimeException("db down"))
                .when(service).updateProbeResult(eq(1), anyBoolean(), isNull());

        recorder.record(1, true, null, T0);
        recorder.record(1, true, null, T0 + 1000);

        verify(service, times(2)).updateProbeResult(1, true, null);
    }

    @Test
    void serverId为null时什么都不做() {
        recorder.record(null, true, null, T0);

        verifyNoMoreInteractions(service);
    }
}
