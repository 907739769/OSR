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

    /** 种子内文件数，不展示，供前端推送时原样回传（见 {@code PushSelectedRequest#files}） */
    private Integer files;

    /** 解析出的集号；为 null 表示整季合集（或电影） */
    private Integer parsedEpisode;

    /** 区间匹配的区间结尾集号（如 S01E01-E02 对应 parsedEpisode=1, parsedEpisodeEnd=2）；非区间匹配为 null */
    private Integer parsedEpisodeEnd;
    /** 解析出的片名，资源搜索页「转为订阅」时拿它去搜 TMDb */
    private String parsedTitle;
    /** 解析出的季号；为 null 且也没有集号时多半是电影 */
    private Integer parsedSeason;
    /** 来源站点开了 H&R 考核 */
    private boolean hitAndRun;

    /** 站点详情页链接（RSS 的 comments 元素，只收 http/https），没有为 null */
    private String detailUrl;

    /**
     * description 的第一段（站点模板里的别名列表那一段），供用户辨认这是哪部作品；
     * 没有或过长（多半是剧情简介）为 null，见 {@code DescriptionAliases#leadSegment}
     */
    private String subtitle;

    /**
     * 资源搜索页专用：识别出的作品 TMDb ID。优先用索引器给的（分类判得出电影/剧集时），
     * 没有时按解析标题走 TMDb 匹配；识别不出为 null。
     */
    private String matchedTmdbId;

    /** 识别用的媒体类型口径 TV / MOVIE，与 TmdbSearchService 同一套取值 */
    private String mediaType;

    /** 识别出的作品中文规范名（TMDb 缺中文翻译时退回原名） */
    private String matchedTitle;

    /** 识别出的作品年份（TMDb 首播/上映年，不是种子标题里那个可能是本季播出年的数字） */
    private String matchedYear;

    /** 这部作品已经在当前用户可见的订阅里（按 tmdbId + 媒体类型判）——用户据此知道这条是补集还是新剧 */
    private boolean subscribed;
    /**
     * 资源搜索页专用：按全局过滤规则这条会被怎样淘汰的短标签（{@code RejectCode#label}，如「分辨率不在白名单」），
     * null 表示会被放行。资源搜索页<b>只标注、不淘汰</b>——用户正是来看「站上到底有什么」的；
     * 订阅内的候选列表已经按规则滤过，不填这一列。
     */
    private String ruleRejection;
    /** 同上的完整原因（带实际值与阈值），页面上作悬浮提示 */
    private String ruleRejectionDetail;
}
