package com.osr.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * 搜索淘汰原因分布：按 {@code pt_search_log.reason_code} 聚合。
 * <p>
 * 与 {@link PtStatsFailReasonDTO}（下载失败原因）对称，但口径不同——那一侧统计的是
 * 「推送之后下载失败」，本侧统计的是「候选在推送之前就被过滤规则挡掉」。
 * 后者此前完全没有统计，而它恰恰是「订阅一直补不到货」最常见的原因。
 * </p>
 * <p>
 * 按<b>码</b>而不是按原因文案聚合：文案里嵌着实际值（"做种数 3 低于下限 5"），
 * 按文案分组只会得到一堆计数为 1 的碎片。{@link #reason} 是码对应的中文短标签，
 * {@link #code} 保留原始取值供前端筛选与国际化。
 * </p>
 *
 * @author Jack
 */
@Data
public class PtStatsRejectReasonDTO {

    /** 原始码，取值见 {@code RejectCode}；无法识别的历史取值原样透出 */
    private String code;

    /** 中文短标签，如「非免费种」「分辨率不在白名单」 */
    private String reason;

    private long count;
}
