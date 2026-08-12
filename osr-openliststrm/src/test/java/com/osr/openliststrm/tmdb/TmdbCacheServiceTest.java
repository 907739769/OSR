package com.osr.openliststrm.tmdb;

import com.osr.openliststrm.mybatisplus.mapper.TmdbCacheMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 过期缓存清理的分批行为。这块没人调用过，上线前先把边界钉住。
 */
@ExtendWith(MockitoExtension.class)
class TmdbCacheServiceTest {

    @Mock
    private TmdbCacheMapper tmdbCacheMapper;

    @InjectMocks
    private TmdbCacheService service;

    @Test
    void purgeExpired_没有过期行时只查一次就返回() {
        when(tmdbCacheMapper.deleteExpired(any(Date.class), eq(TmdbCacheService.PURGE_BATCH_SIZE))).thenReturn(0);

        assertEquals(0, service.purgeExpired());

        verify(tmdbCacheMapper, times(1)).deleteExpired(any(Date.class), eq(TmdbCacheService.PURGE_BATCH_SIZE));
        verifyNoMoreInteractions(tmdbCacheMapper);
    }

    @Test
    void purgeExpired_删满一批就继续删下一批直到删不满() {
        when(tmdbCacheMapper.deleteExpired(any(Date.class), eq(TmdbCacheService.PURGE_BATCH_SIZE)))
                .thenReturn(TmdbCacheService.PURGE_BATCH_SIZE)
                .thenReturn(TmdbCacheService.PURGE_BATCH_SIZE)
                .thenReturn(7);

        assertEquals(TmdbCacheService.PURGE_BATCH_SIZE * 2 + 7, service.purgeExpired());

        verify(tmdbCacheMapper, times(3)).deleteExpired(any(Date.class), eq(TmdbCacheService.PURGE_BATCH_SIZE));
    }

    @Test
    void purgeExpired_每批都删满时在批次上限处停下不无限循环() {
        when(tmdbCacheMapper.deleteExpired(any(Date.class), eq(TmdbCacheService.PURGE_BATCH_SIZE)))
                .thenReturn(TmdbCacheService.PURGE_BATCH_SIZE);

        int expected = TmdbCacheService.PURGE_BATCH_SIZE * TmdbCacheService.PURGE_MAX_BATCHES;
        assertEquals(expected, service.purgeExpired());

        verify(tmdbCacheMapper, times(TmdbCacheService.PURGE_MAX_BATCHES))
                .deleteExpired(any(Date.class), eq(TmdbCacheService.PURGE_BATCH_SIZE));
    }
}
