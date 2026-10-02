package com.osr.openliststrm.pt.downloader;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.notify.NotificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 剩余空间告警只在三个时刻说话：刚低于告警线、持续低位每满一天、回到告警线以上。
 * 15 分钟一轮，每轮都发就是一天 96 条逐字相同的消息。
 */
class DownloaderSpaceServiceTest {

    private static final long GB = 1024L * 1024 * 1024;

    private IPtDownloaderPlusService downloaderService;
    private DownloaderClientFactory clientFactory;
    private IDownloaderClient client;
    private DownloaderSpaceRegistry registry;
    private DownloaderSpaceService service;
    private MockedStatic<TgHelper> tg;

    @BeforeEach
    void setUp() {
        downloaderService = mock(IPtDownloaderPlusService.class);
        clientFactory = mock(DownloaderClientFactory.class);
        client = mock(IDownloaderClient.class);
        when(clientFactory.get(any())).thenReturn(client);
        registry = new DownloaderSpaceRegistry();
        service = new DownloaderSpaceService(downloaderService, clientFactory, registry);
        tg = mockStatic(TgHelper.class);
    }

    @AfterEach
    void tearDown() {
        tg.close();
    }

    private static PtDownloaderPlus downloader(int id, Double warnGb) {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(id);
        d.setName("qb" + id);
        d.setType("QBITTORRENT");
        d.setEnabled("1");
        d.setFreeSpaceWarnGb(warnGb == null ? null : BigDecimal.valueOf(warnGb));
        return d;
    }

    private void verifyNotified(int times) {
        tg.verify(() -> TgHelper.sendMsg(eq(NotificationType.GENERAL), anyString()), times(times));
    }

    private String lastMessage() {
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        tg.verify(() -> TgHelper.sendMsg(eq(NotificationType.GENERAL), msg.capture()), org.mockito.Mockito.atLeastOnce());
        return msg.getValue();
    }

    @Test
    void 刚低于告警线发一次_一天内不重复_满一天再提醒_回来时说恢复() {
        PtDownloaderPlus d = downloader(1, 50.0);
        Date t0 = new Date();

        assertEquals(DownloaderSpaceService.Transition.ENTERED, service.evaluate(d, 10 * GB, t0));
        assertTrue(lastMessage().contains("低于告警线 50.0 GB"));

        service.evaluate(d, 9 * GB, new Date(t0.getTime() + 3_600_000L));
        verifyNotified(1);

        service.evaluate(d, 8 * GB, new Date(t0.getTime() + 25 * 3_600_000L));
        verifyNotified(2);
        assertTrue(lastMessage().contains("已持续约 25 小时"));

        assertEquals(DownloaderSpaceService.Transition.RECOVERED, service.evaluate(d, 80 * GB, new Date()));
        verifyNotified(3);
        assertNull(registry.low(1));
    }

    @Test
    void 没设告警线就不告警() {
        assertEquals(DownloaderSpaceService.Transition.NONE, service.evaluate(downloader(1, null), 1 * GB, new Date()));
        verifyNotified(0);
    }

    /** 低位期间用户把告警线清空：同样算恢复，否则首页那条待办永远消不掉 */
    @Test
    void 低位期间清空告警线也算恢复() {
        service.evaluate(downloader(1, 50.0), 10 * GB, new Date());

        assertEquals(DownloaderSpaceService.Transition.RECOVERED, service.evaluate(downloader(1, null), 10 * GB, new Date()));
        assertTrue(lastMessage().contains("已不再设告警线"));
    }

    @Test
    void 读不到剩余空间不写登记_也不告警() throws Exception {
        PtDownloaderPlus d = downloader(1, 50.0);
        when(client.freeSpace(any())).thenReturn(null);
        assertNull(service.read(d));

        when(client.freeSpace(any())).thenThrow(new IOException("timeout"));
        assertNull(service.read(d));

        assertNull(registry.snapshot(1));
        verifyNotified(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 一轮检查_统计低位台数_停用或删掉的下载器从登记表忘掉() throws Exception {
        registry.record(9, 1 * GB);
        registry.markLow(9, new DownloaderSpaceRegistry.LowState(new Date(), new Date()));
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader(1, 50.0), downloader(2, 50.0)));
        when(client.freeSpace(any())).thenAnswer(inv -> inv.<PtDownloaderPlus>getArgument(0).getId() == 1 ? 10 * GB : 500 * GB);

        DownloaderSpaceService.CheckOutcome outcome = service.checkAll();

        assertEquals(2, outcome.checked());
        assertEquals(1, outcome.low());
        assertEquals(1, outcome.changed());
        assertNotNull(registry.snapshot(2));
        assertNull(registry.snapshot(9), "id=9 已不在启用列表里");
        assertNull(registry.low(9));
    }

    @Test
    void 体积文案_零字节也写出来() {
        assertEquals("0 MB", DownloaderSpaceService.formatSize(0L));
        assertEquals("1.5 GB", DownloaderSpaceService.formatSize(GB + GB / 2));
        assertEquals("未知", DownloaderSpaceService.formatSize(null));
    }

    @Test
    void 告警线换算() {
        assertEquals(50 * GB, downloader(1, 50.0).freeSpaceWarnBytes());
        assertNull(downloader(1, 0.0).freeSpaceWarnBytes());
        assertNull(downloader(1, null).freeSpaceWarnBytes());
    }
}
