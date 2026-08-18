package com.osr.openliststrm.pt.subscription.dto;

import lombok.Data;

/**
 * 匹配历史的一行：在 {@code pt_search_log} 的基础上把索引器 id 翻成名字。
 * <p>
 * 加索引器这一列是因为同一个种子会被<b>多个索引器各返回一份</b>（去重键是
 * {@code (indexerId, guid)}，跨索引器的同名候选都会保留），而各索引器给出的做种数可能互相
 * 矛盾——一份报 0 被 {@code LOW_SEEDERS} 淘汰、另一份报正数照常推送。页面上只显示标题的话，
 * 用户看到的是"两条一模一样的记录，一条通过一条不通过"，无从判断差别在哪。
 * </p>
 *
 * @author Jack
 */
@Data
public class SearchLogView {

    private Integer id;

    /** 目标集号；-1 表示整季包 */
    private Integer episode;

    /** 日志来源：RSS / SUPPLEMENT / MANUAL */
    private String source;

    private String torrentTitle;

    private Integer indexerId;

    /** 索引器名；索引器已被删除时为空，与下载记录页的处理一致 */
    private String indexerName;

    /** "1" 通过，"0" 淘汰 */
    private String accepted;

    /** 结构化淘汰原因码，取值见 RejectCode；摘要类日志与历史数据为空 */
    private String reasonCode;

    /** 带实际值的淘汰说明，如"做种数 0 低于下限 1" */
    private String reason;

    /** 与 {@code BaseEntity#createTime} 同类型（String，不是 Date） */
    private String createTime;
}
