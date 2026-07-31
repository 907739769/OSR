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

    @BeforeEach
    void setUp() {
        cache = new IndexerCapabilityCache(torznabClient);
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
    void get_TorznabClient返回null时视为NONE而非缓存null() {
        PtIndexerPlus indexer = indexer(3);
        when(torznabClient.getCaps(indexer)).thenReturn(null);

        IndexerCapability result = cache.get(indexer);

        assertEquals(IndexerCapability.NONE, result);
    }
}
