package com.osr.openliststrm.pt.indexer;

import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 索引器 ID 搜索能力的进程内缓存。
 * <p>
 * <b>探测成功的结果永久缓存</b>（进程生命周期内只探一次）：索引器能力配置很少变化，
 * 重启应用即可重新探测，不设 TTL 是刻意的（YAGNI）。
 * </p>
 * <p>
 * <b>探测失败只短期缓存。</b>这是两者必须分开处理的原因：{@link IndexerCapability#NONE}
 * 是一个合法结果（站点确实不支持 imdbid/tmdbid），而失败是「不知道」。旧实现用
 * {@code computeIfAbsent} 把两者一视同仁地永久缓存，于是一次网络抖动、一次限流冷却
 * （{@link IndexerRateLimiter} 在冷却期内会直接快速失败）就足以让该索引器在整个进程
 * 生命周期内永远走不到 ID 精确搜索，只剩关键词兜底——而且没有任何日志说得出为什么，
 * 用户只会觉得"这个站搜出来的东西怎么越来越不准"。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class IndexerCapabilityCache {

    private final TorznabClient torznabClient;
    private final ConcurrentMap<Integer, Entry> cache = new ConcurrentHashMap<>();

    /**
     * 探测失败后多久允许重探（毫秒）。默认 5 分钟：既不永久降级，也不至于每次搜索都去捅
     * 一个已经不通的站点——那会和 {@link IndexerRateLimiter} 的退避对着干。
     */
    private final long retryAfterFailureMillis;

    public IndexerCapabilityCache(
            TorznabClient torznabClient,
            @Value("${pt.indexer.caps-retry-after-failure-ms:300000}") long retryAfterFailureMillis) {
        this.torznabClient = torznabClient;
        this.retryAfterFailureMillis = Math.max(0L, retryAfterFailureMillis);
    }

    /**
     * 缓存项。{@code expireAt == 0} 表示永不过期（探测成功）；
     * 否则是探测失败后允许重探的时间点。
     */
    private record Entry(IndexerCapability capability, long expireAt) {
        boolean valid(long now) {
            return expireAt == 0L || now < expireAt;
        }
    }

    /**
     * @return 该索引器的 ID 搜索能力；探测失败时返回 {@link IndexerCapability#NONE}
     *         （调用方据此退回标题搜索），但该结果只在 {@link #retryAfterFailureMillis} 内有效
     */
    public IndexerCapability get(PtIndexerPlus indexer) {
        Integer id = indexer.getId();
        long now = System.currentTimeMillis();

        Entry cached = cache.get(id);
        if (cached != null && cached.valid(now)) {
            return cached.capability();
        }

        // 刻意不用 computeIfAbsent：它在 mapping function 执行期间持有该 bin 的锁，而这里的
        // mapping function 要发一次 HTTP 请求，ConcurrentHashMap 的文档明确不允许在其中做长计算。
        // 改成「查-探-写」后，并发首访最多让同一个索引器多探一次 caps——GET 幂等，
        // 且 IndexerRateLimiter 本就按索引器串行化请求，代价可以忽略。
        IndexerCapability probed = torznabClient.getCaps(indexer);
        if (probed == null) {
            log.warn("索引器[{}]能力探测失败，本轮按不支持 ID 搜索处理，{} 秒后重探（不会永久降级）",
                    indexer.getName(), retryAfterFailureMillis / 1000);
            cache.put(id, new Entry(IndexerCapability.NONE, now + retryAfterFailureMillis));
            return IndexerCapability.NONE;
        }
        cache.put(id, new Entry(probed, 0L));
        return probed;
    }
}
