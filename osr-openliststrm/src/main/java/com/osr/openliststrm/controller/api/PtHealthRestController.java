package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.core.text.Convert;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.health.EpisodeHealthService;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthReport;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 缺集体检。
 *
 * @author Jack
 */
@Slf4j
@RestController
@RequestMapping("/api/openliststrm/pt-health")
public class PtHealthRestController extends BaseController {

    private final EpisodeHealthService healthService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final SearchSupplementService searchSupplementService;

    public PtHealthRestController(EpisodeHealthService healthService,
                                  IPtSubscriptionPlusService subscriptionService,
                                  SearchSupplementService searchSupplementService) {
        this.healthService = healthService;
        this.subscriptionService = subscriptionService;
        this.searchSupplementService = searchSupplementService;
    }

    /**
     * 当前用户能否看到这条订阅。口径与 {@code PtSubscriptionRestController} 完全一致：
     * 管理员看全部，其余人看自己的与无归属的（{@code owner_user_id IS NULL}，
     * 即本列上线前建的历史订阅）。
     */
    private boolean canAccess(PtSubscriptionPlus sub) {
        if (sub == null) {
            return false;
        }
        return SysUser.isAdmin(getUserId())
                || sub.getOwnerUserId() == null
                || sub.getOwnerUserId().equals(getUserId());
    }

    /**
     * 一次性算出「哪些剧还缺着、缺了多久、为什么还缺」。
     * <p>
     * 无分页：这个页面的价值在于一眼看完全部问题，分页会让"一共几部有问题"这个
     * 最重要的数字退到分页器里去。真实数据量由 ACTIVE 订阅的缺集数决定，
     * 正常情况下是几十行的量级。
     * </p>
     */
    @GetMapping
    public Result<EpisodeHealthReport> report(
            @RequestParam(value = "includeIgnored", required = false, defaultValue = "false") boolean includeIgnored) {
        return Result.success(healthService.report(this::canAccess, includeIgnored));
    }

    /**
     * 把订阅从缺集体检里忽略/取消忽略。
     * <p>
     * <b>只影响体检页的可见性与逾期缺集提醒，不影响 RSS 匹配、自动补搜、手动搜索。</b>
     * 这是它与「暂停订阅」的根本区别：暂停会让订阅彻底停止工作，而忽略表达的是
     * 「继续留着，万一哪天有资源还是要抓，只是别再提醒我」。没有这个出口的话，
     * 一部片源根本不存在的老剧会永远躺在列表里、按周反复提醒，用户很快会把整类通知关掉——
     * 连真正该看的那些一起丢。
     * </p>
     * <p>
     * 逐条校验归属再批量写，理由同 {@code enableAutoSearch}：直接把前端传来的 id 拼进
     * UPDATE 的 IN，等于给了一个改别人订阅配置的接口。
     * </p>
     *
     * @return 实际生效的条数（无权操作的会被过滤掉）
     */
    @PostMapping("/ignore")
    public Result<Integer> ignore(@RequestParam("ids") String ids,
                                  @RequestParam(value = "ignored", required = false, defaultValue = "true") boolean ignored) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要操作的订阅");
        }
        List<Integer> requested;
        try {
            requested = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
        } catch (NumberFormatException e) {
            return Result.error("订阅ID格式不正确");
        }
        List<Integer> accessible = subscriptionService.listByIds(requested).stream()
                .filter(this::canAccess)
                .map(PtSubscriptionPlus::getId)
                .toList();
        if (accessible.isEmpty()) {
            return Result.error("没有可操作的订阅");
        }
        int updated = subscriptionService.updateHealthIgnored(accessible, ignored);
        // 取消忽略时顺带清掉通知去重指纹：否则这条订阅上次通知的指纹还在，
        // 缺集没变的话下一轮会被判成「已经报过」而静默跳过，用户点了「取消忽略」
        // 却再也收不到提醒
        if (!ignored) {
            accessible.forEach(id -> subscriptionService.updateOverdueNotifyState(id, null, null));
        }
        log.info("{}缺集体检，订阅 {} 条：{}", ignored ? "忽略" : "取消忽略", updated, accessible);
        return Result.success(updated);
    }

    /**
     * 批量开启自动补搜。
     * <p>
     * 体检页存在的直接原因就是「{@code auto_search} 默认关、用户得逐条手动开，而哪几条该开无处可查」，
     * 因此这个动作必须能<b>就地</b>完成——把用户打发回订阅列表逐条编辑，等于这个页面只完成了一半。
     * </p>
     * <p>
     * 走 {@code LambdaUpdateWrapper} 只 set 这一列，不用 {@code updateById(实体)}：后者在
     * MyBatis-Plus 默认的 {@code NOT_NULL} 策略下会把实体上所有非 null 字段一并写回，
     * 而调用方手里那份订阅是查询时刻的快照，会把补搜链路刚写入的
     * {@code last_search_time} 覆盖回旧值——这个坑在 {@code updateAutoSearchMissState} 的
     * 注释里有完整记录。
     * </p>
     */
    @PostMapping("/enable-auto-search")
    public Result<Integer> enableAutoSearch(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要开启自动补搜的订阅");
        }
        List<Integer> requested;
        try {
            requested = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
        } catch (NumberFormatException e) {
            return Result.error("订阅ID格式不正确");
        }
        // 逐条校验归属再批量写：不能直接把前端传来的 id 拼进 UPDATE 的 IN，
        // 那等于给了一个改别人订阅配置的接口
        List<Integer> accessible = subscriptionService.listByIds(requested).stream()
                .filter(this::canAccess)
                .map(PtSubscriptionPlus::getId)
                .toList();
        if (accessible.isEmpty()) {
            return Result.error("没有可操作的订阅");
        }
        subscriptionService.update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .in(PtSubscriptionPlus::getId, accessible)
                .set(PtSubscriptionPlus::getAutoSearch, "1"));
        log.info("批量开启自动补搜，订阅 {} 条：{}", accessible.size(), accessible);
        return Result.success(accessible.size());
    }

    /**
     * 对一条订阅立刻补搜它当前所有缺集。
     * <p>
     * 与订阅页那个「搜索补集」不同：那个是逐集的，用户得先挑一集、再改关键词；体检页给出的
     * 结论是「这几集都缺着」，逼用户回去逐集点一遍，等于把体检算出来的东西又扔掉了。
     * 这里走的是自动补搜每轮对单条订阅做的<b>同一件事</b>（{@code searchAndPushMissing}），
     * 因此没开自动补搜的订阅也能就地试一次，不必先改配置。
     * </p>
     * <p>
     * 同步返回而不是异步触发：用户按下按钮就是想知道结果，异步只能回一句"已提交"，
     * 而"搜了但一个都没推动"与"搜出来了"对他接下来的动作完全不同。
     * </p>
     * <p>
     * <b>耗时可能到几分钟</b>：季搜索由 {@code pt.search.indexer-budget-ms} 兜住（默认每个
     * 索引器 30 秒软上限、索引器之间并发），其后还有一段单集补发——季搜索一条都没带回来的集
     * 会各发一次完整的单集检索，由 {@code pt.search.per-episode-fallback-limit}（默认 5 集）与
     * {@code -budget-ms}（默认 180 秒）兜住。前端 {@code searchMissingApi} 的超时按这个上限配，
     * 改这两个配置时要一并看那里。
     * </p>
     */
    @PostMapping("/{subId}/search-missing")
    public Result<String> searchMissing(@PathVariable("subId") Integer subId) {
        PtSubscriptionPlus sub = subscriptionService.getById(subId);
        // 订阅不存在与无权访问回同一句提示：区分开就等于给了一个逐个 id 试探、
        // 枚举出别人订阅了什么的接口（口径同 PtSubscriptionRestController）
        if (!canAccess(sub)) {
            return Result.error("订阅不存在或无权访问");
        }
        if (searchSupplementService.hasNoEnabledIndexer()) {
            return Result.error("没有启用中的索引器，无法搜索。请到「索引器」页面添加或启用至少一个索引器");
        }
        SearchAndPushSummary summary = searchSupplementService.searchAndPushMissing(subId);
        if (summary.isSkipped()) {
            return Result.success("当前没有可搜索的缺集（未播出的集不参与搜索）");
        }
        if (summary.anyPushed()) {
            int pushed = summary.getEpisodesPushed() + (summary.isSeasonPushed() ? 1 : 0);
            return Result.success("已推送 " + pushed + " 个资源到下载器");
        }
        // 落空时把真实原因原样回给用户，不要换成"请检查索引器配置"那种泛化文案——
        // 候选全被 freeOnly 淘汰与压根没搜到候选，处置方向完全相反
        return StringUtils.isNotBlank(summary.getRejectSummary())
                ? Result.error("未推送任何资源：" + summary.getRejectSummary())
                : Result.error("未搜到任何候选资源，可检查订阅标题/季号与索引器配置");
    }
}
