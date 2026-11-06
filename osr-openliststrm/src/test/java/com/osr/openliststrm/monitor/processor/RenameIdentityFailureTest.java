package com.osr.openliststrm.monitor.processor;

import com.osr.openliststrm.rename.MediaIdentity;
import com.osr.openliststrm.rename.MediaIdentityService;
import com.osr.openliststrm.rename.RenameClientProvider;
import com.osr.openliststrm.rename.RenameEventListener;
import com.osr.openliststrm.rename.RenameEventListenerFactoryImpl;

import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.monitor.processor.FileStabilityUtils;
import com.osr.openliststrm.monitor.processor.MediaRenameProcessor;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.rename.cleanup.RenameCleanupService;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.tmdb.TMDbApiService;
import com.osr.openliststrm.tmdb.TMDbClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenameIdentityFailureTest {
    @TempDir Path temp;

    @Test
    void STRM记录暂未落库时不报失败_自动回调就绪后复制且不搜索片名() throws Exception {
        Path source = temp.resolve("Wrong.Title.strm");
        Files.writeString(source, "https://openlist.example/d/Pack/Show.mkv");
        Path target = temp.resolve("target");
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class);
             MockedStatic<FileStabilityUtils> stability = mockStatic(FileStabilityUtils.class)) {
            var scheduler = mock(org.springframework.scheduling.TaskScheduler.class);
            java.util.Queue<Runnable> callbacks = new java.util.ArrayDeque<>();
            when(scheduler.schedule(any(Runnable.class), any(java.time.Instant.class))).thenAnswer(i -> {
                callbacks.add(i.getArgument(0)); return mock(java.util.concurrent.ScheduledFuture.class);
            });
            spring.when(() -> SpringUtils.getBean("virtualScheduledExecutor")).thenReturn(scheduler);
            MediaIdentityService identities = mock(MediaIdentityService.class);
            when(identities.resolve(source))
                    .thenThrow(new com.osr.openliststrm.rename.MediaIdentityPendingException("STRM 记录尚未落库"))
                    .thenReturn(new MediaIdentity("79481", "tv", "3", "2"));
            spring.when(() -> SpringUtils.getBean(MediaIdentityService.class)).thenReturn(identities);
            TMDbApiService api = mock(TMDbApiService.class);
            when(api.getDetails("key", "tv", 79481)).thenReturn("{\"id\":79481,\"name\":\"正确作品\"}");
            spring.when(() -> SpringUtils.getBean(TMDbApiService.class)).thenReturn(api);
            var rules = mock(com.osr.openliststrm.mybatisplus.service.IRenameCategoryRulePlusService.class);
            spring.when(() -> SpringUtils.getBean(com.osr.openliststrm.mybatisplus.service.IRenameCategoryRulePlusService.class)).thenReturn(rules);
            var templates = mock(com.osr.openliststrm.rename.config.IRenameTemplateConfigService.class);
            when(templates.getTemplate()).thenReturn("{{ title }}.{{ extension }}");
            spring.when(() -> SpringUtils.getBean(com.osr.openliststrm.rename.config.IRenameTemplateConfigService.class)).thenReturn(templates);
            var notifier = mock(com.osr.openliststrm.pt.media.LibraryRefreshNotifier.class);
            spring.when(() -> SpringUtils.getBean(com.osr.openliststrm.pt.media.LibraryRefreshNotifier.class)).thenReturn(notifier);
            var tasks = mock(com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService.class);
            spring.when(() -> SpringUtils.getBean(com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService.class)).thenReturn(tasks);
            stability.when(() -> FileStabilityUtils.isFileStable(source)).thenReturn(true);
            OpenListHelper helper = mock(OpenListHelper.class);
            when(helper.isStrm(anyString())).thenReturn(true);
            RenameClientProvider provider = mock(RenameClientProvider.class);
            when(provider.tmdb()).thenReturn(new TMDbClient("key"));
            RenameEventListener listener = mock(RenameEventListener.class);
            MediaRenameProcessor processor = new MediaRenameProcessor(target, provider, helper, mock(OpenlistConfig.class), listener);
            processor.handleOneFile(source, null, null, null, null);
            verifyNoInteractions(listener, api);
            assertEquals(1, callbacks.size());
            callbacks.remove().run();
            verify(listener).onRename(eq(source), any(), argThat(info -> "79481".equals(info.getTmdbId())), eq("tv"));
            verify(listener, never()).onRenameFailed(any(), any(), any(), any(), any());
            verify(api, never()).search(anyString(), anyString(), anyString(), any());
            try (var files = Files.walk(target)) {
                assertEquals(1, files.filter(Files::isRegularFile).count());
            }
            assertTrue(callbacks.isEmpty());
            processor.close();
        }
    }

    @Test
    void 指定ID详情请求失败时只记录失败_不复制不刮削也不搜索() throws Exception {
        Path source = temp.resolve("Wrong.Title.mkv");
        Files.write(source, new byte[]{1, 2, 3});
        Path target = temp.resolve("target");
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class);
             MockedStatic<FileStabilityUtils> stability = mockStatic(FileStabilityUtils.class)) {
            MediaIdentityService identities = mock(MediaIdentityService.class);
            when(identities.resolve(source)).thenReturn(new MediaIdentity("79481", "tv", "3", "2"));
            spring.when(() -> SpringUtils.getBean(MediaIdentityService.class)).thenReturn(identities);
            TMDbApiService api = mock(TMDbApiService.class);
            spring.when(() -> SpringUtils.getBean(TMDbApiService.class)).thenReturn(api);
            stability.when(() -> FileStabilityUtils.isFileStable(source)).thenReturn(true);
            OpenListHelper helper = mock(OpenListHelper.class);
            when(helper.isVideo(anyString())).thenReturn(true);
            OpenlistConfig config = mock(OpenlistConfig.class);
            when(config.getOpenListMinFileSize()).thenReturn("0");
            RenameClientProvider provider = mock(RenameClientProvider.class);
            when(provider.tmdb()).thenReturn(new TMDbClient("key"));
            RenameEventListener listener = mock(RenameEventListener.class);
            new MediaRenameProcessor(target, provider, helper, config, listener)
                    .handleOneFile(source, null, null, null, null);
            assertFalse(Files.exists(target));
            assertTrue(Files.exists(source));
            verify(listener).onRenameFailed(eq(source), eq(target),
                    argThat(info -> "79481".equals(info.getTmdbId())), eq("tv"), contains("79481"));
            verify(listener, never()).onRename(any(), any(), any(), any());
            verify(api, never()).search(anyString(), anyString(), anyString(), any());
        }
    }

    @Test
    void 纠错失败保留旧产物位置_再次成功时清理旧位置并清空失败原因() {
        IRenameDetailPlusService details = mock(IRenameDetailPlusService.class);
        RenameCleanupService cleanup = mock(RenameCleanupService.class);
        RenameEventListenerFactoryImpl factory = new RenameEventListenerFactoryImpl();
        ReflectionTestUtils.setField(factory, "renameDetailService", details);
        ReflectionTestUtils.setField(factory, "cleanupService", cleanup);
        RenameDetailPlus record = new RenameDetailPlus();
        record.setId(1);
        record.setOriginalPath(temp.toString());
        record.setOriginalName("source.mkv");
        record.setNewPath(temp.resolve("电影/未分类/错片").toString());
        record.setNewName("错片.mkv");
        when(details.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(record));
        RenameEventListener listener = factory.create(1);
        Path source = temp.resolve("source.mkv");
        MediaInfo info = new MediaInfo("source.mkv");
        info.setTmdbId("123");
        String oldPath = record.getNewPath();
        listener.onRenameFailed(source, temp, info, "movie", "详情请求失败");
        assertEquals(oldPath, record.getNewPath());
        assertEquals("错片.mkv", record.getNewName());
        assertEquals("详情请求失败", record.getFailReason());
        Path dest = temp.resolve("电影/未分类/正片/正片.mkv");
        doAnswer(i -> {
            assertEquals(oldPath, record.getNewPath());
            return null;
        }).when(cleanup).purgeRelocated(same(record), eq(dest));
        listener.onRename(source, dest, info, "movie");
        verify(cleanup).purgeRelocated(same(record), eq(dest));
        assertEquals("1", record.getStatus());
        assertNull(record.getFailReason());
        assertEquals(dest.getParent().toString(), record.getNewPath());
    }
}
