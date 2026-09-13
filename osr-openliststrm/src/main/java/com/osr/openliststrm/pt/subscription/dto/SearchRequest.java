package com.osr.openliststrm.pt.subscription.dto;

import lombok.Data;

import java.util.List;

/**
 * 搜索补集请求体。
 *
 * @author Jack
 */
@Data
public class SearchRequest {

    /** 目标集号：-1(SubscriptionMatcher.SEASON_PACK)=季包/整部，电影恒为0，剧集单集传具体集号 */
    private int episode;

    /** 搜索关键词，前端按标题/季集号预填，用户可编辑 */
    private String keyword;

    /** 是否启用手动选择模式：true=返回候选列表供用户挑选，false（默认）=自动推送最优结果 */
    private boolean manualSelect;

    /**
     * 限定只搜这几个索引器（站点）。null 或空表示全部启用中的索引器，与引入前行为一致。
     * <p>已停用/已删除的 id 静默忽略；全部都不可用时直接报错，不退回搜全部——
     * 用户明确说了只要这几个站，悄悄扩大范围会推回一个他刻意排除的站点的种子。</p>
     */
    private List<Integer> indexerIds;
}
