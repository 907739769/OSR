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
 * PT 匹配/过滤日志：记录每个候选种子在 RSS 轮询或搜索补集时是否被淘汰及原因，
 * 供前端"为什么这一轮没抓到"排查用。只在候选已匹配到某个订阅时才落库，
 * RSS 全量拉取里跟任何订阅都不沾边的种子不记录——那种情况没有订阅上下文可归属。
 * </p>
 *
 * @author Jack
 * @since 2026-07-31
 */
@Getter
@Setter
@TableName("pt_search_log")
public class PtSearchLogPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 订阅ID */
    @TableField("sub_id")
    private Integer subId;

    /** 集号，电影恒为0，-1表示季包 */
    @TableField("episode")
    private Integer episode;

    /** 来源 RSS/SUPPLEMENT */
    @TableField("source")
    private String source;

    /** 候选种子标题；摘要类日志(如"无可用下载器")为空 */
    @TableField("torrent_title")
    private String torrentTitle;

    /** 来源索引器ID，摘要类日志可为空 */
    @TableField("indexer_id")
    private Integer indexerId;

    /** 是否通过过滤 0-否 1-是 */
    @TableField("accepted")
    private String accepted;

    /** 淘汰原因或摘要说明；通过的候选为空 */
    /**
     * 结构化淘汰原因码，取值见 {@code pt.filter.RejectCode}。
     * <p>
     * 与 {@link #reason} 是两用：reason 带具体数值（"做种数 3 低于下限 5"）供逐条排查，
     * 本字段是稳定分类，供聚合与统计——按 reason 分组只会得到一堆计数为 1 的碎片。
     * 摘要类日志（{@code recordSummary}）与历史数据为空。
     * </p>
     */
    @TableField("reason_code")
    private String reasonCode;

    @TableField("reason")
    private String reason;
}
