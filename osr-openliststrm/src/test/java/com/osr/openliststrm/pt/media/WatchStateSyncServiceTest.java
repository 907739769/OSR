package com.osr.openliststrm.pt.media;

import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 观看状态同步里判错了不报错、只会悄悄写错数的几条：多台取并集、读不到的服务器不参与、
 * 一台都读不到时不能覆盖成「一集都没看」、只差毫秒时不重写。
 */
class WatchStateSyncServiceTest {

    private final IPtSubscriptionPlusService subscriptionService = mock(IPtSubscriptionPlusService.class);
    private final IPtMediaServerPlusService mediaServerService = mock(IPtMediaServerPlusService.class);
    private final MediaServerClientFactory factory = mock(MediaServerClientFactory.class);
    private final IMediaServerClient clientA = mock(IMediaServerClient.class);
    private final IMediaServerClient clientB = mock(IMediaServerClient.class);
    private final WatchStateSyncService service = new WatchStateSyncService(subscriptionService, mediaServerService, factory);

    private final PtMediaServerPlus serverA = server(1);
    private final PtMediaServerPlus serverB = server(2);
    private PtSubscriptionPlus sub;

    private static PtMediaServerPlus server(int id) {
        PtMediaServerPlus s = new PtMediaServerPlus();
        s.setId(id);
        s.setName("server" + id);
        return s;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sub = new PtSubscriptionPlus();
        sub.setId(9);
        sub.setTmdbId("1396");
        sub.setMediaType("TV");
        sub.setSeason(1);
        when(mediaServerService.listActive()).thenReturn(List.of(serverA, serverB));
        when(subscriptionService.list(any(Wrapper.class))).thenReturn(List.of(sub));
        when(factory.get(same(serverA))).thenReturn(clientA);
        when(factory.get(same(serverB))).thenReturn(clientB);
    }

    @Test
    void 多台服务器取并集_最近观看取最晚() throws IOException {
        when(clientA.watchState(same(serverA), anyString(), any(), anyBoolean()))
                .thenReturn(new WatchState(Set.of(1, 2), new Date(1_000_000_000L)));
        when(clientB.watchState(same(serverB), anyString(), any(), anyBoolean()))
                .thenReturn(new WatchState(Set.of(2, 3), new Date(2_000_000_000L)));

        assertEquals(1, service.syncAll());

        verify(subscriptionService).updateWatchState(eq(9), eq(3), eq(new Date(2_000_000_000L)));
    }

    /** 读不到（Emby 没配用户 ID）不是「一集都没看」：不能拿它覆盖另一台读到的结果 */
    @Test
    void 读不到的服务器不参与() throws IOException {
        when(clientA.watchState(same(serverA), anyString(), any(), anyBoolean())).thenReturn(null);
        when(clientB.watchState(same(serverB), anyString(), any(), anyBoolean()))
                .thenReturn(new WatchState(Set.of(1), null));

        service.syncAll();

        verify(subscriptionService).updateWatchState(eq(9), eq(1), eq(null));
    }

    @Test
    void 一台都读不到_原值不动() throws IOException {
        sub.setWatchedCount(5);
        when(clientA.watchState(same(serverA), anyString(), any(), anyBoolean())).thenReturn(null);
        when(clientB.watchState(same(serverB), anyString(), any(), anyBoolean())).thenThrow(new IOException("down"));

        assertEquals(0, service.syncAll());

        verify(subscriptionService, never()).updateWatchState(anyInt(), any(), any());
    }

    /** 库里 DATETIME 只到秒，只差毫秒时不能每小时白写一遍 */
    @Test
    void 只差毫秒_视为没变化() throws IOException {
        sub.setWatchedCount(1);
        sub.setLastWatchedTime(new Date(1_700_000_000_000L));
        when(clientA.watchState(same(serverA), anyString(), any(), anyBoolean()))
                .thenReturn(new WatchState(Set.of(1), new Date(1_700_000_000_123L)));
        when(clientB.watchState(same(serverB), anyString(), any(), anyBoolean())).thenReturn(null);

        assertEquals(0, service.syncAll());
    }

    @Test
    void Emby响应_只算Played的集() {
        JSONArray items = JSONArray.parse("[{\"IndexNumber\":1,\"UserData\":{\"Played\":true,\"LastPlayedDate\":\"2026-09-20T12:00:00.0000000Z\"}},"
                + "{\"IndexNumber\":2,\"UserData\":{\"Played\":false}},{\"IndexNumber\":3}]");

        WatchState state = EmbyClient.toWatchState(items, false);

        assertEquals(Set.of(1), state.watchedEpisodes());
        assertEquals(java.time.Instant.parse("2026-09-20T12:00:00Z"), state.lastWatched().toInstant());
    }

    @Test
    void Emby没配用户ID_返回null() throws IOException {
        PtMediaServerPlus noUser = server(3);
        assertNull(new EmbyClient(new okhttp3.OkHttpClient()).watchState(noUser, "1396", 1, false));
    }
}
