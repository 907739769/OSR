package com.osr.openliststrm.pt.indexer;

import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 索引器 ID 搜索能力的进程内缓存：每个索引器进程生命周期内只探测一次 t=caps。
 * 不落库、不设 TTL——索引器能力配置很少变化，重启应用即可重新探测（YAGNI）。
 *
 * @author Jack
 */
@Component
public class IndexerCapabilityCache {

    private final TorznabClient torznabClient;
    private final ConcurrentMap<Integer, IndexerCapability> cache = new ConcurrentHashMap<>();

    public IndexerCapabilityCache(TorznabClient torznabClient) {
        this.torznabClient = torznabClient;
    }

    public IndexerCapability get(PtIndexerPlus indexer) {
        return cache.computeIfAbsent(indexer.getId(), id -> {
            IndexerCapability capability = torznabClient.getCaps(indexer);
            return capability == null ? IndexerCapability.NONE : capability;
        });
    }
}
