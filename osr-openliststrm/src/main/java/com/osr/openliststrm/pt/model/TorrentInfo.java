package com.osr.openliststrm.pt.model;

import lombok.Data;

import java.util.Date;

/**
 * 统一种子模型，贯穿 indexer → filter → subscription 全流程。
 * <p>
 * 上半部分字段来自索引器响应，parsedXxx 字段由标题解析阶段填充。
 * 未来接入站内搜索、站点原生 RSS 时，新数据源只需产出本模型，下游无需改动。
 * </p>
 *
 * @author Jack
 */
@Data
public class TorrentInfo {

    /** 种子原始标题，过滤与解析的输入 */
    private String title;

    /** 种子 info hash，部分索引器不提供，可为空 */
    private String infoHash;

    /**
     * 索引器给出的条目唯一标识（RSS &lt;guid&gt;），用于下载记录去重。
     * <p>
     * 不含 apikey 等凭据，比 downloadUrl 更适合做去重键（downloadUrl 常带 apikey，
     * apikey 重置后同一种子的 downloadUrl 会变化，导致去重失效）。
     * 索引器未提供 guid 时，由 {@link com.osr.openliststrm.pt.indexer.TorznabParser}
     * 回退为 downloadUrl，本字段因此恒不为空。
     * </p>
     */
    private String guid;

    /** .torrent 下载链接或磁力链，推送下载器时使用 */
    private String downloadUrl;

    /** 体积（字节） */
    private long size;

    /** 做种数 */
    private int seeders;

    /** 下载数 */
    private int peers;

    /**
     * 下载量系数：0=免费，0.5=50%，1=正常计量。
     * 索引器未提供时默认按正常计量处理，避免把收费种误判为免费。
     */
    private double downloadVolumeFactor = 1.0;

    /** 发布时间原始字符串，保留索引器返回的格式 */
    private String pubDate;

    /** 来源索引器 ID */
    private Integer indexerId;

    // ---------- 以下字段由标题解析阶段填充（计划3） ----------

    /** 解析出的作品标题 */
    private String parsedTitle;

    /** 解析出的英文标题（种子中英混排时的英文部分），与 parsedTitle 一起参与订阅匹配 */
    private String parsedTitleEn;

    /** 解析出的年份 */
    private String parsedYear;

    /** 解析出的季号，电影为 null */
    private Integer parsedSeason;

    /** 解析出的集号，电影为 null */
    private Integer parsedEpisode;

    /** 解析出的集数区间结尾，如标题为 "S01E01-03" 时为 3；非区间或电影为 null */
    private Integer parsedEpisodeEnd;

    /** 解析出的分辨率，如 1080p、2160p */
    private String parsedResolution;

    /** 解析出的媒介来源，如 WEB-DL、BluRay、Remux */
    private String parsedSource;

    /** 解析出的发布组，如 CHDWEB；未解析出时为 null */
    private String parsedReleaseGroup;

    /** 种子描述（RSS &lt;description&gt;），部分 PT 站会在描述中标注字幕信息 */
    private String description;

    /** 解析后的发布时间；原始字符串见 {@link #pubDate}，本字段不变动 pubDate */
    private Date parsedPubTime;

    /**
     * 是否为免费种。用容差比较而非直接 == 0，避免浮点解析误差。
     */
    public boolean isFree() {
        return downloadVolumeFactor < 0.0001;
    }
}
