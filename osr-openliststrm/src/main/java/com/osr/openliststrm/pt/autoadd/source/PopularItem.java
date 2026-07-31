package com.osr.openliststrm.pt.autoadd.source;

import lombok.Data;

import java.util.List;

/**
 * 热门榜单候选条目的统一模型，屏蔽不同数据源（TMDb 榜单、未来的豆瓣榜单等）的字段差异。
 * <p>
 * tmdbId 为空时（如豆瓣源）需要额外的转换步骤补全后才能进入建订阅流程，
 * 本期只有 {@link TmdbPopularSource} 一个实现，tmdbId 恒不为空。
 * </p>
 *
 * @author Jack
 */
@Data
public class PopularItem {

    /** TMDb ID，非 TMDb 源可能为空，需后续转换补全 */
    private String tmdbId;

    /** 豆瓣 ID，供未来豆瓣数据源使用，TMDb 源恒为空 */
    private String doubanId;

    /** IMDb ID，供跨源转换用，取不到时为空 */
    private String imdbId;

    /** 媒体类型 TV / MOVIE */
    private String mediaType;

    /** 标题 */
    private String title;

    /** 原始标题 */
    private String originalTitle;

    /** 年份 */
    private String year;

    /** 评分 */
    private Double voteAverage;

    /** 评分人数 */
    private Integer voteCount;

    /** TMDb 类型 ID 列表，供规则的类型排除过滤使用 */
    private List<Integer> genreIds;

    /** 海报路径 */
    private String posterPath;
}
