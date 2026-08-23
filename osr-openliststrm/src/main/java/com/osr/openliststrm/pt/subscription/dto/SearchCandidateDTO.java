package com.osr.openliststrm.pt.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 搜索补集候选种子展示 DTO，供用户在手动选择模式下挑选。
 * <p>
 * 包含种子标题、体积、做种数、免费状态、分辨率、来源、索引器名称等信息，
 * 用户据此决定推送哪个版本的资源到下载器。
 * </p>
 *
 * @author Jack
 */
@Data
@Builder
@AllArgsConstructor
public class SearchCandidateDTO {

    /** 种子原始标题 */
    private String title;

    /** 体积（字节），前端自行格式化展示 */
    private long size;

    /** 做种数 */
    private int seeders;

    /** 下载数 */
    private int peers;

    /** 是否免费种 */
    private boolean free;

    /**
     * 下载量系数原值：0=免费，0.5=半价，1=正常计量。
     * <p>
     * 单有上面的布尔 {@code free} 不够：前端推送时只能反推出 0 或 1，半价促销种(0.5)会被压成 1.0，
     * 导致 {@code SortDimension} 的促销优先排序在手动推送路径上失真。这里原样回传原值，
     * 前端推送时照原样带回即可。
     * </p>
     */
    private double downloadVolumeFactor;

    /** 解析出的分辨率，如 1080p、2160p */
    private String resolution;

    /** 解析出的媒介来源，如 WEB-DL、BluRay、Remux */
    private String source;

    /** 来源索引器名称 */
    private String indexerName;

    /** 来源索引器 ID，用于后续推送时定位 */
    private int indexerId;

    /** 种子 GUID（去重标识），用于后续推送时定位 */
    private String guid;

    /** 种子下载链接（.torrent/磁力链接），推送到下载器时必需 */
    private String downloadUrl;

    /** 种子 InfoHash */
    private String infoHash;

    /** 种子标题的解析年份 */
    private String parsedYear;

    /** 种子发布时间 */
    private String pubDate;

    /**
     * 种子描述原文，仅供前端在推送时原样回传，不做展示。
     * <p>
     * 不能省：推送接口拿到的是前端回传的几个字段，后端会用它们<b>重新</b>跑一遍
     * {@code SubscriptionEngine#fillParsed}，而集号有一类只写在 description 里
     * （{@code … | S01E51-E66 | 内封简繁字幕}，标题标成整季，见 {@code DescriptionEpisode}）。
     * 不带这一段的话，搜索时明明解析出了 E51-E66 的种子在推送时又变回「有季无集」，
     * 占位范围与用户在列表里看到的对不上。
     * </p>
     */
    private String description;

    /** 解析出的集号；为 null 表示整季合集（或电影） */
    private Integer parsedEpisode;

    /** 区间匹配的区间结尾集号（如 S01E01-E02 对应 parsedEpisode=1, parsedEpisodeEnd=2）；非区间匹配为 null */
    private Integer parsedEpisodeEnd;
}
