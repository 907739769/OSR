package com.osr.openliststrm.pt.indexer;

import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexerCapabilityCacheTest {

    @Mock
    private TorznabClient torznabClient;

    private IndexerCapabilityCache cache;

    /** 失败重探间隔取 5 分钟，与生产默认值一致，用于验证"失败结果在窗口内被缓存" */
    private static final long RETRY_WINDOW_MS = 300_000L;

    @BeforeEach
    void setUp() {
        cache = new IndexerCapabilityCache(torznabClient, RETRY_WINDOW_MS);
    }

    private PtIndexerPlus indexer(int id) {
        PtIndexerPlus i = new PtIndexerPlus();
        i.setId(id);
        i.setName("idx-" + id);
        return i;
    }

    @Test
    void get_首次探测并缓存_第二次不再调用TorznabClient() {
        PtIndexerPlus indexer = indexer(1);
        IndexerCapability cap = new IndexerCapability(true, false, true, false);
        when(torznabClient.getCaps(indexer)).thenReturn(cap);

        IndexerCapability first = cache.get(indexer);
        IndexerCapability second = cache.get(indexer);

        assertSame(cap, first);
        assertSame(cap, second);
        verify(torznabClient, times(1)).getCaps(indexer);
    }

    @Test
    void get_不同索引器分别缓存() {
        // PtIndexerPlus 继承自 BaseEntity（@Data），equals 只比较 createTime/updateTime/params——
        // 两个未落库的新实例会被判定为"相等"，必须用 same() 按引用区分两个桩，否则后调用会
        // 命中前一个桩（同 AGENTS.md 记录的 *Plus 实体 mock 打桩陷阱）。
        PtIndexerPlus idx1 = indexer(1);
        PtIndexerPlus idx2 = indexer(2);
        when(torznabClient.getCaps(same(idx1))).thenReturn(new IndexerCapability(true, false, false, false));
        when(torznabClient.getCaps(same(idx2))).thenReturn(new IndexerCapability(false, true, false, false));

        assertEquals(true, cache.get(idx1).movieImdbSupported());
        assertEquals(true, cache.get(idx2).movieTmdbSupported());
        verify(torznabClient, times(1)).getCaps(same(idx1));
        verify(torznabClient, times(1)).getCaps(same(idx2));
    }

    @Test
    void get_探测失败_对外返回NONE让调用方退回标题搜索() {
        PtIndexerPlus indexer = indexer(3);
        when(torznabClient.getCaps(indexer)).thenReturn(null);

        assertEquals(IndexerCapability.NONE, cache.get(indexer));
    }

    @Test
    void get_探测失败_窗口内不重复探测() {
        // 不能每次调用都去捅一个已经不通的站点，那会和 IndexerRateLimiter 的退避对着干
        PtIndexerPlus indexer = indexer(4);
        when(torznabClient.getCaps(indexer)).thenReturn(null);

        cache.get(indexer);
        cache.get(indexer);
        cache.get(indexer);

        verify(torznabClient, times(1)).getCaps(indexer);
    }

    @Test
    void get_探测失败_窗口过后重新探测_不永久降级() {
        // 这是本次修复的核心：旧实现用 computeIfAbsent 把失败结果永久缓存，
        // 一次网络抖动就让该索引器在整个进程生命周期内再也走不到 ID 精确搜索
        IndexerCapabilityCache noWindow = new IndexerCapabilityCache(torznabClient, 0L);
        PtIndexerPlus indexer = indexer(5);
        IndexerCapability recovered = new IndexerCapability(true, false, true, false);
        when(torznabClient.getCaps(indexer)).thenReturn(null, recovered);

        assertEquals(IndexerCapability.NONE, noWindow.get(indexer));
        // 窗口为 0，下一次调用即重探，此时站点已恢复
        assertEquals(recovered, noWindow.get(indexer));
        verify(torznabClient, times(2)).getCaps(indexer);
    }

    @Test
    void get_探测成功但确实不支持任何ID_永久缓存不重探() {
        // NONE 是合法的探测结果（站点确实不支持 imdbid/tmdbid），必须与"探测失败"区分开：
        // 前者永久缓存，后者短期缓存。两者塌成一个值就没法分别处理了
        PtIndexerPlus indexer = indexer(6);
        IndexerCapabilityCache noWindow = new IndexerCapabilityCache(torznabClient, 0L);
        when(torznabClient.getCaps(indexer)).thenReturn(IndexerCapability.NONE);

        assertEquals(IndexerCapability.NONE, noWindow.get(indexer));
        assertEquals(IndexerCapability.NONE, noWindow.get(indexer));

        verify(torznabClient, times(1)).getCaps(indexer);
    }
}
