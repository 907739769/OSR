package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;

/**
 * TMDb API 响应缓存实体
 */
@Getter
@Setter
@TableName("tmdb_cache")
public class TmdbCache implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 缓存键（请求URL摘要）
     */
    @TableField("cache_key")
    private String cacheKey;

    /**
     * 缓存类型：search/details/season/images 等
     */
    @TableField("cache_type")
    private String cacheType;

    /**
     * 缓存的JSON响应文本
     */
    @TableField("response_data")
    private String responseData;

    /**
     * 过期时间
     */
    @TableField("expire_time")
    private Date expireTime;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private Date createTime;
}
