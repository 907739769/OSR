package com.osr.openliststrm.pt.autoadd.source;

import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;

import java.util.List;

/**
 * 热门榜单数据源。Spring 会自动收集所有实现类，新增数据源（如未来的豆瓣榜单）
 * 只需新增一个实现类，无需改动 {@link com.osr.openliststrm.pt.autoadd.AutoAddPopularService} 的调度逻辑。
 *
 * @author Jack
 */
public interface PopularSource {

    /**
     * 该数据源是否能处理规则里配置的 source 取值。
     */
    boolean supports(String source);

    /**
     * 拉取候选列表，已翻页聚合，未做过滤/去重。
     */
    List<PopularItem> fetch(PtAutoAddRulePlus rule);
}
