package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 重命名分类规则配置
 * </p>
 *
 * @author Jack
 * @since 2026-07-20
 */
@Getter
@Setter
@TableName("rename_category_rule")
public class RenameCategoryRulePlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 媒体类型 movie/tv
     */
    @TableField("media_type")
    private String mediaType;

    /**
     * 排序序号，越小优先级越高
     */
    @TableField("seq")
    private Integer seq;

    /**
     * 逗号分隔的TMDB genre id，空表示不限
     */
    @TableField("genre_ids")
    private String genreIds;

    /**
     * 逗号分隔语言码，空表示不限
     */
    @TableField("original_languages")
    private String originalLanguages;

    /**
     * 逗号分隔国家码，空表示不限
     */
    @TableField("origin_countries")
    private String originCountries;

    /**
     * 命中后的目标目录名，同时兼作展示名
     */
    @TableField("target_dir")
    private String targetDir;

    /**
     * 是否兜底规则 0-否 1-是
     */
    @TableField("is_fallback")
    private String isFallback;
}
