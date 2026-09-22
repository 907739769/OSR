package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddLogPlus;

/**
 * <p>
 * 热门自动订阅执行日志 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-29
 */
public interface IPtAutoAddLogPlusService extends IService<PtAutoAddLogPlus> {

    /**
     * 这部作品的这一季是否曾被<b>任一规则</b>自动订过（存在 ADDED 记录）。
     * <p>
     * 「订过、而现在订阅表里没有」只有一种可能：用户手动删了它。据此不再自动加回来——
     * 否则只要它还挂在榜上，删掉的订阅下一轮就会原样回来并重新开始下载。
     * </p>
     *
     * @param season 电影传 null
     */
    boolean everAdded(String tmdbId, String mediaType, Integer season);

    /**
     * 来源条目（豆瓣 subject id）最近的一条日志，没有时返回 null。
     * 用来复用上一轮的 TMDb 匹配结果，省掉每轮按标题重搜 TMDb。
     */
    PtAutoAddLogPlus latestBySourceItem(String sourceItemId, String mediaType);

    /**
     * 同一条规则对同一个条目是否已经记过这个结果。
     * 跳过类结果（已存在/已删除/过滤/未匹配）每轮都会重复成立，只在第一次记，
     * 否则执行日志很快被逐字相同的行占满，真正要看的 ADDED 被挤出最近 100 条。
     */
    boolean alreadyLogged(PtAutoAddLogPlus entry);

    /**
     * 清理早于 {@code before} 的非 ADDED 日志。
     * <p>
     * ADDED 永久保留：{@link #everAdded} 靠它判断「用户删过这部」，清掉的话被删的订阅
     * 会在保留期满后被自动加回来。它的量也小——每轮最多 max_add_per_run 条。
     * </p>
     *
     * @param before 格式 yyyy-MM-dd HH:mm:ss，与 create_time 的写入格式一致
     * @return 删除的行数
     */
    int purgeSkippedBefore(String before);
}
