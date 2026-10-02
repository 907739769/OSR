package com.osr.openliststrm.pt.media;

import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注意：{@code *Plus} 实体继承的 equals 只比时间戳，两台不同 id 的服务器会被判「相等」，打桩与校验一律用 {@code same()}。
 */
class LibraryRefreshNotifierTest {

    private static final LibraryRoot TV = new LibraryRoot("电视剧", "/media/电视剧", "1");
    private static final LibraryRoot MOVIE = new LibraryRoot("电影", "/media/电影", "2");

    private IPtMediaServerPlusService serverService;
    private MediaServerClientFactory factory;
    private TaskScheduler scheduler;
    private ApplicationEventPublisher events;
    private LibraryRefreshNotifier notifier;

    @BeforeEach
    void setUp() {
        serverService = mock(IPtMediaServerPlusService.class);
        factory = mock(MediaServerClientFactory.class);
        scheduler = mock(TaskScheduler.class);
        events = mock(ApplicationEventPublisher.class);
        notifier = new LibraryRefreshNotifier(serverService, factory, scheduler, events);
    }

    private static PtMediaServerPlus server(int id, String notify, String mapping) {
        PtMediaServerPlus s = new PtMediaServerPlus();
        s.setId(id);
        s.setName("服务器" + id);
        s.setType("EMBY");
        s.setUrl("http://emby" + id + ":8096");
        s.setEnabled("1");
        s.setLibraryNotify(notify);
        s.setPathMapping(mapping);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<RefreshTarget> captureTargets(IMediaServerClient client, PtMediaServerPlus server) throws IOException {
        ArgumentCaptor<List<RefreshTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).refreshPaths(same(server), captor.capture());
        return captor.getValue();
    }

    // ---------------- 映射 / 过滤 / 收缩（纯逻辑） ----------------

    @Test
    void plan_按映射换成服务器视角_不在库下的目录单独列出() {
        PtMediaServerPlus s = server(1, "1", "/data/media => /media");

        LibraryRefreshNotifier.Plan plan = LibraryRefreshNotifier.plan(s, List.of(TV, MOVIE), List.of(
                "/data/media/电视剧/三体/Season 1",
                "/data/strm/电视剧/三体"));

        assertEquals(List.of(new RefreshTarget(TV, "/media/电视剧/三体/Season 1")), plan.targets());
        assertEquals("/data/strm/电视剧/三体", plan.unmatched().keySet().iterator().next());
    }

    @Test
    void collapse_祖先目录在列时子目录不再单发() {
        List<RefreshTarget> targets = LibraryRefreshNotifier.collapse(TV, List.of(
                "/media/电视剧/三体",
                "/media/电视剧/三体/Season 1",
                "/media/电视剧/漫长的季节/Season 1",
                "/media/电视剧/三体"));

        assertEquals(List.of(new RefreshTarget(TV, "/media/电视剧/三体"),
                new RefreshTarget(TV, "/media/电视剧/漫长的季节/Season 1")), targets);
    }

    @Test
    void collapse_同库目录太多改刷整库() {
        List<String> many = IntStream.range(0, LibraryRefreshNotifier.MAX_PATHS_PER_LIBRARY + 1)
                .mapToObj(i -> "/media/电视剧/剧" + i).toList();

        assertEquals(List.of(new RefreshTarget(TV, null)), LibraryRefreshNotifier.collapse(TV, many));
    }

    @Test
    void collapse_其中就有库目录本身时刷整库() {
        assertEquals(List.of(new RefreshTarget(TV, null)),
                LibraryRefreshNotifier.collapse(TV, List.of("/media/电视剧/", "/media/电视剧/三体")));
    }

    // ---------------- 发送 ----------------

    @Test
    void flush_只通知开了通知的服务器_一季多集合并成一个目录() throws Exception {
        PtMediaServerPlus on = server(1, "1", "/data/media => /media");
        PtMediaServerPlus off = server(2, "0", null);
        when(serverService.listActive()).thenReturn(List.of(on, off));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(TV, MOVIE));

        notifier.submitFile(Path.of("/data/media/电视剧/三体/Season 1/S01E01.strm"));
        notifier.submitFile(Path.of("/data/media/电视剧/三体/Season 1/S01E02.strm"));
        notifier.submitFile(Path.of("/data/media/电视剧/三体/Season 1/S01E02.nfo"));
        notifier.flushNow();

        assertEquals(List.of(new RefreshTarget(TV, "/media/电视剧/三体/Season 1")), captureTargets(client, on));
        verify(client, never()).listLibraryRoots(same(off));
    }

    @Test
    void flush_全部不在库下时不发请求() throws Exception {
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(TV));

        notifier.submitFile(Path.of("/data/strm/电视剧/三体/S01E01.strm"));
        notifier.flushNow();

        verify(client, never()).refreshPaths(any(), anyList());
    }

    @Test
    void flush_一台失败不影响另一台() throws Exception {
        PtMediaServerPlus broken = server(1, "1", null);
        PtMediaServerPlus ok = server(2, "1", null);
        when(serverService.listActive()).thenReturn(List.of(broken, ok));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(broken))).thenThrow(new IOException("连不上"));
        when(client.listLibraryRoots(same(ok))).thenReturn(List.of(TV));

        notifier.submitDir(Path.of("/media/电视剧/三体"));
        notifier.flushNow();

        assertEquals(List.of(new RefreshTarget(TV, "/media/电视剧/三体")), captureTargets(client, ok));
    }

    @Test
    void flush_不支持的类型跳过() throws Exception {
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(null);

        notifier.submitDir(Path.of("/media/电视剧/三体"));
        notifier.flushNow();

        verify(client, never()).refreshPaths(any(), anyList());
    }

    @Test
    void flush_库目录有缓存_发送失败后作废重拉() throws Exception {
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(TV));

        notifier.submitDir(Path.of("/media/电视剧/a"));
        notifier.flushNow();
        notifier.submitDir(Path.of("/media/电视剧/b"));
        notifier.flushNow();
        verify(client, times(1)).listLibraryRoots(same(on));

        doThrow(new IOException("500")).when(client).refreshPaths(same(on), anyList());
        notifier.submitDir(Path.of("/media/电视剧/c"));
        notifier.flushNow();
        notifier.submitDir(Path.of("/media/电视剧/d"));
        notifier.flushNow();
        verify(client, times(2)).listLibraryRoots(same(on));
    }

    @Test
    void flush_发过的目录不会下一批重发() throws Exception {
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(TV));

        notifier.submitDir(Path.of("/media/电视剧/a"));
        notifier.flushNow();
        notifier.flushNow();

        verify(client, times(1)).refreshPaths(same(on), anyList());
    }

    @Test
    void flush_没有开通知的服务器时不拉库目录() throws Exception {
        when(serverService.listActive()).thenReturn(List.of(server(1, "0", null)));

        notifier.submitDir(Path.of("/media/电视剧/a"));
        notifier.flushNow();

        verify(factory, never()).get(any());
    }

    // ---------------- 提前对账事件 ----------------

    /** 真发出去了才让对账提前跑：媒体库没收到通知就不会有变化，白跑一轮对账 */
    @Test
    void 事件_发出通知后发布一次_带上目标数() throws Exception {
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(TV, MOVIE));

        notifier.submitDir(Path.of("/media/电视剧/a"));
        notifier.submitDir(Path.of("/media/电影/b"));
        notifier.flushNow();

        verify(events, times(1)).publishEvent(new LibraryRefreshedEvent(2));
    }

    @Test
    void 事件_全部不在库下或发送失败时不发布() throws Exception {
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(TV));

        notifier.submitDir(Path.of("/data/strm/a"));
        notifier.flushNow();

        doThrow(new IOException("500")).when(client).refreshPaths(same(on), anyList());
        notifier.submitDir(Path.of("/media/电视剧/a"));
        notifier.flushNow();

        verify(events, never()).publishEvent(any(Object.class));
    }

    // ---------------- 删除 ----------------

    /** 一季删光、季目录已被回收：通知最近一个还存在的目录（剧目录），Plex 的局部扫描只认存在的目录 */
    @Test
    void 删除_所在目录已被回收时上溯到还存在的目录(@TempDir Path tmp) throws Exception {
        Path tvRoot = tmp.resolve("电视剧");
        Path show = tvRoot.resolve("三体");
        Path season = show.resolve("Season 1");
        Files.createDirectories(season);
        Files.delete(season);
        LibraryRoot root = new LibraryRoot("电视剧", tvRoot.toString(), "1");
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(root));

        notifier.submitDeleted(season.resolve("S01E01.strm"));
        notifier.submitDeleted(season.resolve("S01E01.nfo"));
        notifier.flushNow();

        assertEquals(List.of(new RefreshTarget(root, show.toString())), captureTargets(client, on));
    }

    /** 整部剧删光：上溯到库目录本身，改刷整库 */
    @Test
    void 删除_整部剧删光时刷整库(@TempDir Path tmp) throws Exception {
        Path tvRoot = tmp.resolve("电视剧");
        Files.createDirectories(tvRoot);
        LibraryRoot root = new LibraryRoot("电视剧", tvRoot.toString(), "1");
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(root));

        notifier.submitDeleted(tvRoot.resolve("三体").resolve("Season 1").resolve("S01E01.strm"));
        notifier.flushNow();

        assertEquals(List.of(new RefreshTarget(root, null)), captureTargets(client, on));
    }

    @Test
    void 删除_与新增同一批时一起发且去重(@TempDir Path tmp) throws Exception {
        Path season = tmp.resolve("电视剧").resolve("三体").resolve("Season 1");
        Files.createDirectories(season);
        LibraryRoot root = new LibraryRoot("电视剧", tmp.resolve("电视剧").toString(), "1");
        PtMediaServerPlus on = server(1, "1", null);
        when(serverService.listActive()).thenReturn(List.of(on));
        IMediaServerClient client = mock(IMediaServerClient.class);
        when(factory.get(any())).thenReturn(client);
        when(client.listLibraryRoots(same(on))).thenReturn(List.of(root));

        notifier.submitFile(season.resolve("S01E02.strm"));
        notifier.submitDeleted(season.resolve("S01E01.strm"));
        notifier.flushNow();

        assertEquals(List.of(new RefreshTarget(root, season.toString())), captureTargets(client, on));
    }

    @Test
    void 删除_也会安排定时() {
        notifier.submitDeleted(Path.of("/media/电视剧/三体/S01E01.strm"));

        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    // ---------------- 防抖 ----------------

    @Test
    void 防抖_一批只安排一次定时_静默期内有新提交就顺延() {
        List<Instant> scheduledAt = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> {
            scheduledAt.add(inv.getArgument(1));
            return null;
        });
        when(serverService.listActive()).thenReturn(List.of());

        notifier.submitDir(Path.of("/media/电视剧/a"));
        notifier.submitDir(Path.of("/media/电视剧/b"));
        assertEquals(1, scheduledAt.size(), "同一批不该重复安排定时");

        // 到点时最后一次提交还不满静默期（刚刚才提交过）：顺延而不是发出
        notifier.onTimer();
        assertEquals(2, scheduledAt.size());
        assertTrue(scheduledAt.get(1).isAfter(Instant.now()));
        verify(serverService, never()).listActive();
    }
}
