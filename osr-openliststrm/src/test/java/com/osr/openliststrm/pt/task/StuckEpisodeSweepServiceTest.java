package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StuckEpisodeSweepServiceTest {

    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtMediaServerPlusService mediaServerService;

    private StuckEpisodeSweepService service() {
        return new StuckEpisodeSweepService(episodeService, subscriptionService, mediaServerService, 12, 3);
    }

    private void withActiveMediaServer() {
        PtMediaServerPlus server = new PtMediaServerPlus();
        server.setId(1);
        when(mediaServerService.getActive()).thenReturn(server);
    }

    private PtSubscriptionEpisodePlus stuck(int id, int episode, Integer failCount) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setId(id);
        ep.setSubId(10);
        ep.setEpisode(episode);
        ep.setState("IN_FLIGHT");
        ep.setFailCount(failCount);
        ep.setDownloadId(100);
        return ep;
    }

    @Test
    void 没有启用中的媒体服务器_整体跳过不查不改() {
        // 那种配置下 queryLibrary 恒返回空集，任何集都不可能被推进 IN_LIBRARY。
        // 清扫会把每一次正常完成的下载都判成卡死，变成无限重下
        when(mediaServerService.getActive()).thenReturn(null);

        assertEquals(0, service().sweep());
        verify(episodeService, never()).listStuckInFlight(anyInt());
        verify(episodeService, never()).update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class));
    }

    @Test
    void 卡死的集退回缺失并累加失败次数() {
        withActiveMediaServer();
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of(stuck(501, 27, 0), stuck(502, 28, null)));
        when(episodeService.update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class))).thenReturn(true);
        when(subscriptionService.getById(10)).thenReturn(sub());

        assertEquals(2, service().sweep());

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, times(2)).update(captor.capture(), any(Wrapper.class));
        assertTrue(captor.getAllValues().stream().allMatch(ep -> "MISSING".equals(ep.getState())));
        // 累加计数是防跑飞的关键：一个永远对不上账的集会重下重扫，必须能熔断
        assertTrue(captor.getAllValues().stream().allMatch(ep -> ep.getFailCount() == 1));
    }

    @Test
    void 累计失败达阈值的集_转BLOCKED停止自动重试() {
        withActiveMediaServer();
        // 已失败 2 次，本轮是第 3 次，达到阈值
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of(stuck(501, 27, 2)));
        when(episodeService.update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class))).thenReturn(true);
        when(subscriptionService.getById(10)).thenReturn(sub());

        assertEquals(1, service().sweep());

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService).update(captor.capture(), any(Wrapper.class));
        assertEquals("BLOCKED", captor.getValue().getState());
        assertEquals(3, captor.getValue().getFailCount());
    }

    @Test
    void 条件更新未命中的集不计入结果() {
        // 与追踪轮询重叠时，集可能已被别的路径推进（比如对账刚好把它标成入库）
        withActiveMediaServer();
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of(stuck(501, 27, 0)));
        when(episodeService.update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class))).thenReturn(false);

        assertEquals(0, service().sweep());
        verify(subscriptionService, never()).getById(any());
    }

    @Test
    void 没有卡死的集_不发通知() {
        withActiveMediaServer();
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of());

        assertEquals(0, service().sweep());
        verify(episodeService, never()).update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class));
    }

    private PtSubscriptionPlus sub() {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(10);
        sub.setTitle("Some Show");
        return sub;
    }
}
