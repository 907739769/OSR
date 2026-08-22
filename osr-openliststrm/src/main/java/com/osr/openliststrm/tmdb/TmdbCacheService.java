package com.osr.openliststrm.tmdb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.TmdbCache;
import com.osr.openliststrm.mybatisplus.mapper.TmdbCacheMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * TMDb API 响应缓存服务
 * 默认 TTL：24小时（1440分钟）
 */
@Slf4j
@Service
public class TmdbCacheService {

    /** 默认缓存有效期（分钟） */
    public static final int DEFAULT_TTL_MINUTES = 1440;

    /** 清理过期缓存时的单批条数 */
    static final int PURGE_BATCH_SIZE = 1000;

    /** 单轮清理最多执行的批次数，兜住「删除速度赶不上过期速度」时的无限循环 */
    static final int PURGE_MAX_BATCHES = 100;

    @Autowired
    private TmdbCacheMapper tmdbCacheMapper;

    /**
     * 获取有效缓存；若无有效缓存则返回 null。
     *
     * @param cacheKey  请求URL摘要
     * @param cacheType 缓存类型
     * @return 缓存的JSON文本，或 null（未命中/已过期）
     */
    public String getCachedResponse(String cacheKey, String cacheType) {
        LambdaQueryWrapper<TmdbCache> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TmdbCache::getCacheKey, cacheKey)
               .eq(TmdbCache::getCacheType, cacheType)
               .gt(TmdbCache::getExpireTime, new Date())
               .last("LIMIT 1");
        TmdbCache cache = tmdbCacheMapper.selectOne(wrapper);
        if (cache != null) {
            // 刻意不记「缓存命中」：命中是正常路径上的高频事件，没有任何诊断价值——
            // 没人会因为「命中了」去查什么。实测它一家占掉全量日志的 22%（一次对账 236 行）。
            // 未命中那一侧由 cacheResponse 的「缓存写入」间接标记（未命中必然回源并回填），
            // 想看命中率应该走指标而不是逐条日志。
            return cache.getResponseData();
        }
        return null;
    }

    /**
     * 存储 API 响应到缓存（upsert：同 key+type 的旧记录会被删除）。
     *
     * @param cacheKey      请求URL摘要
     * @param cacheType     缓存类型
     * @param responseData  JSON响应文本
     * @param ttlMinutes    有效期（分钟），传 0 使用默认值
     */
    public void cacheResponse(String cacheKey, String cacheType, String responseData, int ttlMinutes) {
        if (ttlMinutes <= 0) {
            ttlMinutes = DEFAULT_TTL_MINUTES;
        }
        // 唯一索引 uk_cache_key(cache_key, cache_type) 支持单条 INSERT ... ON DUPLICATE KEY UPDATE，
        // 替代原先 delete + insert 两次往返，同时消除两者之间的竞态窗口
        TmdbCache cache = new TmdbCache();
        cache.setCacheKey(cacheKey);
        cache.setCacheType(cacheType);
        cache.setResponseData(responseData);
        long expireMillis = System.currentTimeMillis() + (long) ttlMinutes * 60_000L;
        cache.setExpireTime(new Date(expireMillis));
        cache.setCreateTime(new Date());
        tmdbCacheMapper.upsert(cache);
        log.debug("TMDb缓存写入: type={}, key={}, ttl={}min", cacheType, cacheKey, ttlMinutes);
    }

    /**
     * 清理所有过期缓存，由 {@link TmdbCachePurgeTask} 定时调用。
     * <p>
     * 过期行不会被 {@link #getCachedResponse} 读到，但也不会自己消失：只有同一 (cache_key, cache_type)
     * 再次被请求时才会被 upsert 覆盖，那些「刮完一次就再没人问过」的 key 会永久留在表里。
     * 因此必须有人定期删——本方法就是那个人。
     * </p>
     * <p>
     * 分批删除而不是一条 DELETE 删干净：首次启用时表里可能已经积压了几十万行，
     * 单条语句会长时间持有行锁并撑大 undo log，把刮削链路一起卡住。
     * </p>
     *
     * @return 本轮实际删除的总行数
     */
    public int purgeExpired() {
        Date now = new Date();
        int total = 0;
        for (int batch = 0; batch < PURGE_MAX_BATCHES; batch++) {
            int deleted = tmdbCacheMapper.deleteExpired(now, PURGE_BATCH_SIZE);
            total += deleted;
            if (deleted < PURGE_BATCH_SIZE) {
                // 最后一批没删满，说明已经删完了
                return logPurged(total);
            }
        }
        // 删满了所有批次仍可能有剩余，留给下一轮——这里只是兜住单轮无限循环，不是错误
        log.warn("TMDb过期缓存单轮清理达到上限{}批（{}条），剩余部分下一轮继续",
                PURGE_MAX_BATCHES, PURGE_MAX_BATCHES * PURGE_BATCH_SIZE);
        return total;
    }

    private int logPurged(int total) {
        if (total > 0) {
            log.info("已清理{}条过期TMDb缓存", total);
        }
        return total;
    }
}
