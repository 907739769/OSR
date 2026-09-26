package com.osr.openliststrm.dashboard;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderHealthRegistry;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TodoSignalServiceTest {

    private final IPtDownloaderPlusService downloaderService = mock(IPtDownloaderPlusService.class);
    private final IPtMediaServerPlusService mediaServerService = mock(IPtMediaServerPlusService.class);
    private final IPtDownloadRecordPlusService recordService = mock(IPtDownloadRecordPlusService.class);
    private final DownloaderHealthRegistry registry = new DownloaderHealthRegistry();
    private final TodoSignalService service =
            new TodoSignalService(downloaderService, mediaServerService, recordService, registry);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(1);
        d.setName("家里qB");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d));
        PtMediaServerPlus m = new PtMediaServerPlus();
        m.setName("Emby");
        m.setLastCheckError("连接 http://10.0.0.5:8096 超时");
        when(mediaServerService.list(any(Wrapper.class))).thenReturn(List.of(m));
        when(recordService.count(any(Wrapper.class))).thenReturn(3L);
    }

    /** 一次超时多半是抖动：连续失败到阈值才报离线，恢复后立刻消失 */
    @Test
    void 下载器连续失败到阈值才算离线_恢复即清除() {
        registry.recordFailure(1, "timeout");
        assertTrue(service.signals(PtStatsScope.ALL).offlineDownloaders().isEmpty());

        registry.recordFailure(1, "timeout");
        assertEquals("家里qB", service.signals(PtStatsScope.ALL).offlineDownloaders().get(0).name());

        registry.recordSuccess(1);
        assertTrue(service.signals(PtStatsScope.ALL).offlineDownloaders().isEmpty());
    }

    /** 报错里常带内网地址：只给管理员 */
    @Test
    void 非管理员_看得到名字但看不到错误详情() {
        TodoSignalService.TodoSignals signals = service.signals(PtStatsScope.of(false, 7L));

        assertEquals("Emby", signals.unhealthyMediaServers().get(0).name());
        assertNull(signals.unhealthyMediaServers().get(0).detail());
        assertEquals("连接 http://10.0.0.5:8096 超时",
                service.signals(PtStatsScope.ALL).unhealthyMediaServers().get(0).detail());
    }

    /** 用户忽略掉的失败不该再挂在待办里，否则那一项永远消不掉 */
    @Test
    @SuppressWarnings("unchecked")
    void 失败下载计数_扣掉已忽略的() {
        ArgumentCaptor<Wrapper<PtDownloadRecordPlus>> captor = ArgumentCaptor.forClass(Wrapper.class);

        assertEquals(3L, service.signals(PtStatsScope.ALL).unresolvedFailedDownloads());

        verify(recordService).count(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("fail_ignored <>"), sql);
        assertTrue(sql.contains("NOT EXISTS"), sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 某一路查询失败_只把那一路置空_其余照常() {
        when(recordService.count(any(Wrapper.class))).thenThrow(new RuntimeException("db down"));

        TodoSignalService.TodoSignals signals = service.signals(PtStatsScope.ALL);

        assertNull(signals.unresolvedFailedDownloads());
        assertEquals(1, signals.unhealthyMediaServers().size());
    }
}
