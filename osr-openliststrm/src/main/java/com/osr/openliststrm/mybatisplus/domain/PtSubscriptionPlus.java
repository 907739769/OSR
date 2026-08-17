package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * <p>
 * PT 订阅
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Getter
@Setter
@TableName("pt_subscription")
public class PtSubscriptionPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** TMDb ID */
    @TableField("tmdb_id")
    private String tmdbId;

    /** IMDb ID（如 tt0125664），建订阅时从 TMDb 详情/external_ids 获取，用于索引器 ID 精确搜索 */
    @TableField("imdb_id")
    private String imdbId;

    /** 媒体类型 TV/MOVIE */
    @TableField("media_type")
    private String mediaType;

    /** 作品标题 */
    @TableField("title")
    private String title;

    /** TMDb 原始语言标题：日剧/韩剧等场景是日文/韩文，不是英文；与中文 title 一起用于匹配种子标题 */
    @TableField("original_title")
    private String originalTitle;

    /** 真正的英文标题（原始语言非英文时来自 TMDb alternative_titles 的 US/GB 别名），PT 站种子标题多为英文，匹配时优先用它 */
    @TableField("english_title")
    private String englishTitle;

    /** 年份 */
    @TableField("year")
    private String year;

    /** 季号；电影恒为 0（哨兵值，不用 null——否则唯一索引对电影失效） */
    @TableField("season")
    private Integer season;

    /** 总集数，电影恒为 1 */
    @TableField("total_episodes")
    private Integer totalEpisodes;

    /** 状态 ACTIVE/COMPLETED/PAUSED */
    @TableField("status")
    private String status;

    /** 该订阅是否参与洗版 0-否 1-是。全局开关（pt_upgrade_config.enabled）关闭时本项无效 */
    @TableField("upgrade_enabled")
    private String upgradeEnabled;

    /** 订阅级过滤覆盖(JSON)，空表示全用全局配置 */
    @TableField("filter_override")
    private String filterOverride;

    /** 订阅级下载追踪覆盖(JSON)，当前仅支持 zombieTimeoutHours 键，空表示全用全局配置 */
    @TableField("download_override")
    private String downloadOverride;

    /** 指定下载器，空表示用唯一启用的那个 */
    @TableField("downloader_id")
    private Integer downloaderId;

    /** TMDb 海报路径，列表展示用 */
    @TableField("poster_path")
    private String posterPath;

    /** 上次命中种子的时间 */
    @TableField("last_match_time")
    private Date lastMatchTime;

    /** 是否开启自动定时补搜 0-否 1-是 */
    @TableField("auto_search")
    private String autoSearch;

    /** 上次发起搜索补集的时间，用于自动补搜到期判断与前端展示 */
    @TableField("last_search_time")
    private Date lastSearchTime;

    /**
     * 定期自动补搜的连续落空次数：0 表示上一轮有命中或还没跑过，N&gt;0 表示已连续 N 轮什么都没推成。
     * <p>
     * 一个字段担两件事：{@code > 0} 精确等于旧的「上一轮已落空」布尔，仍用于通知去重；
     * 次数本身用于按次退避（见 {@code AutoSearchService#effectiveIntervalMillis}）——
     * 片源确实不存在的老剧不该永远每 24 小时打满一整轮索引器请求，
     * 而这件事用户从现象上根本看不出来（日志里每轮都"正常地"搜了一遍）。
     * </p>
     */
    @TableField("last_auto_search_no_result")
    private Integer lastAutoSearchNoResult;

    /**
     * 上次自动补搜落空时的淘汰原因码指纹（排序去重、不含计数，见
     * {@code SearchLogService#digestRejectionsSince}）。
     * <p>
     * 落空通知本身按「首次落空」去重，但原因<b>种类</b>变了要再发一次：「压根没搜到候选」
     * 与「候选全被 freeOnly 淘汰」的处置方向完全相反，只按次数去重会把这次翻转吃掉，
     * 而那恰恰是用户最需要知道的一次变化。指纹刻意不含计数——摘要里
     * 「98 个非免费种」的数字每轮都在变，含进去等于每轮都通知，退回刷屏。
     * </p>
     */
    @TableField("last_auto_search_reject_sign")
    private String lastAutoSearchRejectSign;

    /**
     * 上次「逾期缺集」通知时，这条订阅逾期缺集的指纹（集数 + 排序去重的集号，见
     * {@code EpisodeHealthNotifyService#signatureOf}）。
     * <p>
     * 逾期缺集与补搜落空不同：它<b>天天都在</b>，不通知去重的话每轮都会重发同一条。
     * 指纹变了（新缺了一集、或补上了一集）立刻再通知一次，没变则按周期重提醒——
     * 只按"发过就不再发"处理的话，一部永远补不上的剧提醒一次之后就再无声息，
     * 而它恰恰是最该被记住的那一部。
     * </p>
     * <p>
     * 指纹带集数前缀是为了压低截断后的碰撞：集号串可能很长（长篇动画一季上百集），
     * 超过列宽要截断，而两批不同的缺集常常共享一长串相同的前缀。
     * </p>
     */
    @TableField("last_overdue_notify_sign")
    private String lastOverdueNotifySign;

    /** 上次发出「逾期缺集」通知的时间，配合指纹做周期性重提醒；NULL 表示从未通知过 */
    @TableField("last_overdue_notify_time")
    private Date lastOverdueNotifyTime;

    /**
     * 订阅归属人(sys_user.user_id)。NULL = 无归属的公共订阅，所有人可见——
     * 本列是后加的，历史订阅全为 NULL，若把 NULL 当成"归属于某个不存在的人"，
     * 升级后所有老订阅会从非管理员的列表里整批消失。
     * 通知定向也读这个值（见 {@code NotifyTarget#owner}）。
     */
    @TableField("owner_user_id")
    private Long ownerUserId;

    /** 排序方式：lastMatchTime=按上次命中时间倒序；其余/空=默认按 id 倒序。仅供列表查询用，不落库 */
    @TableField(exist = false)
    private String sortBy;
}
