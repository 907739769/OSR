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
            log.debug("TMDb缓存命中: type={}, key={}", cacheType, cacheKey);
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
     * 清理所有过期缓存（可由定时任务调用）
     */
    public void purgeExpired() {
        LambdaQueryWrapper<TmdbCache> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(TmdbCache::getExpireTime, new Date());
        int deleted = tmdbCacheMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("已清理{}条过期TMDb缓存", deleted);
        }
    }
}
