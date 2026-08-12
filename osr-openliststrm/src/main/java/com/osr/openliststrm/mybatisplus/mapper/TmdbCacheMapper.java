package com.osr.openliststrm.mybatisplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osr.openliststrm.mybatisplus.domain.TmdbCache;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * TMDb API 响应缓存 Mapper
 */
public interface TmdbCacheMapper extends BaseMapper<TmdbCache> {

    /**
     * 按唯一键 (cache_key, cache_type) 插入或更新缓存，一条 SQL 替代原先的 delete + insert 两次往返。
     */
    int upsert(@Param("item") TmdbCache item);

    /**
     * 删除 expire_time 早于 now 的行，单次最多 limit 条。
     * <p>
     * 写成显式 SQL 而不是 {@code delete(wrapper.last("LIMIT n"))}：DELETE 带 LIMIT 是分批清理的关键，
     * 让它以拼接片段的形式藏在 Wrapper 里，日后任何人改动条件都可能把它弄丢而不自知。
     * {@code ORDER BY expire_time} 走 idx_tmdb_cache_expire，保证每批都从最早过期的开始删。
     * </p>
     *
     * @return 实际删除的行数
     */
    int deleteExpired(@Param("now") Date now, @Param("limit") int limit);
}
