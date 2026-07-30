package com.ruoyi.openliststrm.pt.subscription.dto;

import lombok.Data;

/**
 * 手动选择模式下的推送请求：用户从前端候选列表中选择一个种子后，
 * 将种子的必要信息提交到后端，后端创建 TorrentInfo 后走已有的 pushBest 推送链路。
 *
 * @author Jack
 */
@Data
public class PushSelectedRequest {

    /** 目标集号（与 SearchRequest.episode 语义一致） */
    private int episode;

    /** 种子原始标题 */
    private String title;

    /** 体积（字节） */
    private long size;

    /** 做种数 */
    private int seeders;

    /** 下载数 */
    private int peers;

    /**
     * 下载量系数：0=免费，0.5=50%，1=正常计量。
     * <p>
     * <b>必须是包装类型 Double 而非原始 double</b>：原始类型的缺省值是 {@code 0.0}，而 0.0 的
     * 语义恰好是"免费种"。任何没带这个字段的请求（旧版前端、脚本、重放的 body）都会被当成
     * 免费种，从而绕过 {@code freeOnly} 过滤把全价种推进下载器——
     * {@link com.ruoyi.openliststrm.pt.model.TorrentInfo} 特意把默认值定成 1.0 并注明
     * "绝不能默认成免费"，这个防御在 DTO 边界上曾被静默翻转成它的反面。
     * 取值见 {@link #resolveDownloadVolumeFactor()}，null 一律按正常计量兜底。
     * </p>
     */
    private Double downloadVolumeFactor;

    /**
     * 安全取值：未提供时按 1.0（正常计量）兜底，宁可把免费种当收费种少下一次，
     * 也不能把收费种当免费种下进来——后者直接消耗下载量，是分享率与封号风险的来源。
     */
    public double resolveDownloadVolumeFactor() {
        return downloadVolumeFactor == null ? 1.0 : downloadVolumeFactor;
    }

    /** 来源索引器 ID */
    private int indexerId;

    /** 种子 GUID（去重标识） */
    private String guid;

    /** .torrent 下载链接或磁力链 */
    private String downloadUrl;

    /** 种子 info hash，可选 */
    private String infoHash;

    /** 种子描述（RSS &lt;description&gt;） */
    private String description;

    /** 发布时间原始字符串 */
    private String pubDate;
}
