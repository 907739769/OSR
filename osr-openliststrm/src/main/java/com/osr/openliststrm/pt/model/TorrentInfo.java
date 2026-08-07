package com.osr.openliststrm.pt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

    /**
     * 种子内文件总数（Torznab {@code files} 属性），索引器未提供时为 null。
     * <p>
     * <b>推送前唯一能证伪「整季包」的硬信号</b>。标题命名成季包（有季无集）只说明发布者
     * 这么写，一个 8 集季包和一个「按季包命名、实际只含 1 集」的种子在标题上完全一样，
     * 体积也分不开——8GB 可能是 8 集 × 1GB，也可能是 1 集 Remux。但包内集数不可能超过
     * 文件总数，{@code files} 一旦给出就是一个可靠的上界。
     * </p>
     * <p>
     * 只能<b>单向</b>使用：files 小是"覆盖不了那么多集"的证据，files 大不代表集数多
     * （nfo/字幕/封面都算文件）。为 null 时一律按"判不出来"处理，维持既有行为——
     * 取向与 {@code SeasonPackRange} 一致，判不出来就当整季包，交给
     * {@code DownloadTrackService} 拿下载器的真实文件列表事后对账兜底。
     * </p>
     */
    private Integer files;

    /** 来源索引器 ID */
    private Integer indexerId;

    /**
     * 来源站点是否有 H&R 考核，由调用方按 {@link #indexerId} 填充。
     * <p>
     * 是<b>站点</b>属性而非种子属性：Torznab 协议没有标准的 H&R 字段，索引器也不会逐条告知
     * 哪个种子要考核，只能按站点整体判定。默认 false——判不出来时按"不考核"处理，
     * 宁可少规避一次，也不能凭空把一批正常候选当成 H&R 淘汰掉。
     * </p>
     */
    private boolean hitAndRun;

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

    /**
     * 解析出的质量标签，如 REMUX / HDR10 / ATMOS / 10BIT / Dolby Vision。
     * <p>
     * 来源是 {@code SourceAndGroupExtractor} 抽出的 {@code MediaInfo.tags}——这些信息一直
     * 都被解析出来了，只是此前没有字段承接，在 {@code SubscriptionEngine#fillParsed} 就被丢掉。
     * 取值大小写不统一（枚举里多为大写，Dolby Vision 是混合大小写），下游比较一律大小写不敏感。
     * </p>
     * <p>恒不为 null：解析不出任何标签时是空列表，调用方不必判空。</p>
     */
    private List<String> parsedTags = new ArrayList<>();

    /** 种子描述（RSS &lt;description&gt;），部分 PT 站会在描述中标注字幕信息 */
    private String description;

    /** 解析后的发布时间；原始字符串见 {@link #pubDate}，本字段不变动 pubDate */
    private Date parsedPubTime;

    /**
     * 这个种子覆盖多少集，供体积判定归一化用。默认 1（单集资源/电影）。
     * <p>
     * 不是解析出来的原始字段，而是由调用方结合<b>目标订阅</b>算出来的——区间包看
     * parsedEpisode/parsedEpisodeEnd 就够，但整季包的标题里没有任何集数信息，
     * 只能取订阅的总集数。计算口径统一在
     * {@code com.osr.openliststrm.pt.filter.EpisodeCountResolver}。
     * </p>
     */
    private int episodeCount = 1;

    /**
     * 是否为免费种。用容差比较而非直接 == 0，避免浮点解析误差。
     */
    public boolean isFree() {
        return downloadVolumeFactor < 0.0001;
    }

    /**
     * 折算到每一集的体积。
     * <p>
     * 体积阈值（下限/上限/偏好值）天然是按<b>单集</b>设定的，而 {@link #size} 是整个种子的：
     * 一个 26 集的季包体积是单集的二十几倍，拿它直接跟单集阈值比，上限会把所有季包一刀切光，
     * 下限会把所有单集资源放行。归一到每集才能让同一份阈值对单集、区间包、季包都成立。
     * </p>
     * <p>{@link #episodeCount} 恒 &gt;= 1 由本方法兜底，调用方不必自己防 0 除。</p>
     */
    public long getSizePerEpisode() {
        return episodeCount <= 1 ? size : size / episodeCount;
    }
}
