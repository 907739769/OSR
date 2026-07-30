package com.ruoyi.openliststrm.pt.subscription.dto;

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

    /** 解析出的集号；为 null 表示整季合集（或电影） */
    private Integer parsedEpisode;

    /** 区间匹配的区间结尾集号（如 S01E01-E02 对应 parsedEpisode=1, parsedEpisodeEnd=2）；非区间匹配为 null */
    private Integer parsedEpisodeEnd;
}
