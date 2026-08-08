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

    private PtSubscriptionEpisodePlus confirmed(int id, int episode) {
        PtSubscriptionEpisodePlus ep = stuck(id, episode, 0);
        ep.setFileConfirmed("1");
        return ep;
    }

    // ---------- 文件已下好、只是没传上网盘：只告警不重下 ----------

    @Test
    void 文件已确认的集_只告警绝不退回重下() {
        // 用户实测场景：下载早就完成了，卡住的是上传到网盘那一段（秒传要等别人先传过，
        // 否则大文件真传跨天）。重下一遍解决不了任何问题——本地文件本来就在——
        // 只会白费带宽、多背一份 H&R 保种义务，还会累加 fail_count 把好端端的集熔断
        withActiveMediaServer();
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of(confirmed(501, 27)));
        when(subscriptionService.getById(10)).thenReturn(sub());

        assertEquals(0, service().sweep());
        verify(episodeService, never()).update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class));
    }

    @Test
    void 同一批里_已确认的保留未确认的释放() {
        // 一个季包同时产生两种集：包里真有的（上传慢）与压根没有的（多占）。
        // 判据不同，处置也必须不同，不能一刀切
        withActiveMediaServer();
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of(
                confirmed(501, 27), stuck(502, 28, 0)));
        when(episodeService.update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class))).thenReturn(true);
        when(subscriptionService.getById(10)).thenReturn(sub());

        assertEquals(1, service().sweep());

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, times(1)).update(captor.capture(), any(Wrapper.class));
        assertEquals("MISSING", captor.getValue().getState());
    }

    @Test
    void 文件已确认的集_提醒按集限频不会每轮都发() {
        // 清扫每 10 分钟一轮，不限频的话一个传了三天的大文件会发四百多条一模一样的通知
        withActiveMediaServer();
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of(confirmed(501, 27)));
        when(subscriptionService.getById(10)).thenReturn(sub());

        StuckEpisodeSweepService svc = service();
        svc.sweep();
        svc.sweep();
        svc.sweep();

        // 三轮只查一次订阅 = 只组装过一次通知
        verify(subscriptionService, times(1)).getById(10);
    }

    @Test
    void 上传成功后集不再卡住_限频表清空以便下次立刻提醒() {
        withActiveMediaServer();
        when(subscriptionService.getById(10)).thenReturn(sub());
        StuckEpisodeSweepService svc = service();

        when(episodeService.listStuckInFlight(12)).thenReturn(List.of(confirmed(501, 27)));
        svc.sweep();
        // 上传成功、集入库，本轮已不在卡死列表里
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of());
        svc.sweep();
        // 又卡住了：不该被上一次的时间戳压住
        when(episodeService.listStuckInFlight(12)).thenReturn(List.of(confirmed(501, 27)));
        svc.sweep();

        verify(subscriptionService, times(2)).getById(10);
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
