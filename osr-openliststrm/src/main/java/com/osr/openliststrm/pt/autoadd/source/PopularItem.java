package com.osr.openliststrm.pt.autoadd.source;

import lombok.Data;

import java.util.List;

/**
 * 热门榜单候选条目的统一模型，屏蔽不同数据源（TMDb 榜单、未来的豆瓣榜单等）的字段差异。
 * <p>
 * tmdbId 为空时（豆瓣源）需要 {@code PopularItemResolver} 先按标题搜 TMDb 补全，
 * 才能进入建订阅流程——整条链路（订阅表、集号对齐、索引器的外部 ID 检索）都是围绕 tmdbId 建的。
 * </p>
 *
 * @author Jack
 */
@Data
public class PopularItem {

    /** TMDb ID，非 TMDb 源可能为空，需后续转换补全 */
    private String tmdbId;

    /** 豆瓣 ID，仅豆瓣数据源有值，TMDb 源恒为空 */
    private String doubanId;

    /** 来源侧条目链接（豆瓣条目页），仅豆瓣数据源有值。匹配不上 TMDb 时靠它回查是哪个条目 */
    private String sourceUrl;

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

    /**
     * 来源侧给出的季号，如豆瓣标题「瑞克和莫蒂 第九季」里的 9；给不出时为 null。
     * 它比下游按「最新季」兜底更准——榜单上的续季条目说的就是那一季。
     */
    private Integer seasonNumber;

    /** 评分 */
    private Double voteAverage;

    /** 评分人数 */
    private Integer voteCount;

    /** TMDb 类型 ID 列表，供规则的类型排除过滤使用 */
    private List<Integer> genreIds;

    /** 海报路径 */
    private String posterPath;
}
