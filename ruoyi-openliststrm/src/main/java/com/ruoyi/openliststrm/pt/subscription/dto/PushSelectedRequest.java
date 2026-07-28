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

    /** 下载量系数：0=免费，0.5=50%，1=正常计量 */
    private double downloadVolumeFactor;

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
