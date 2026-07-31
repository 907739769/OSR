package com.osr.openliststrm.mybatisplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osr.openliststrm.mybatisplus.domain.TmdbCache;
import org.apache.ibatis.annotations.Param;

/**
 * TMDb API 响应缓存 Mapper
 */
public interface TmdbCacheMapper extends BaseMapper<TmdbCache> {

    /**
     * 按唯一键 (cache_key, cache_type) 插入或更新缓存，一条 SQL 替代原先的 delete + insert 两次往返。
     */
    int upsert(@Param("item") TmdbCache item);
}
