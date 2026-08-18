package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.core.text.Convert;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.PushOutcome;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.BatchOperationResult;
import com.osr.openliststrm.pt.subscription.dto.PushSelectedRequest;
import com.osr.openliststrm.pt.subscription.dto.SearchLogView;
import com.osr.openliststrm.pt.subscription.dto.SearchRequest;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.osr.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.osr.openliststrm.pt.subscription.dto.SupplementResult;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PT 订阅 REST API 控制器
 *
 * @author Jack
 * @date 2026-07-27
 */
@Slf4j
@RestController
@RequestMapping("/api/openliststrm/pt-subscriptions")
public class PtSubscriptionRestController extends BaseCrudRestController<IPtSubscriptionPlusService, PtSubscriptionPlus> {

    @Autowired
    private SubscriptionService subscriptionBiz;

    @Autowired
    private TmdbSearchService tmdbSearchService;

    @Autowired
    private IPtSubscriptionEpisodePlusService episodeService;

    @Autowired
    private SearchSupplementService searchSupplementService;

    @Autowired
    private SubscriptionSearchOnCreateTrigger searchOnCreateTrigger;

    @Autowired
    private IPtSearchLogPlusService searchLogService;

    @Autowired
    private IPtIndexerPlusService indexerService;

    /**
     * 当前登录用户是否可以看到/操作所有订阅。管理员可以；其余用户只能碰自己的订阅
     * 和无归属的公共订阅（{@code owner_user_id IS NULL}，即本列上线前建的历史订阅）。
     */
    private boolean canAccessAll() {
        return SysUser.isAdmin(getUserId());
    }

    /** 当前登录用户能否操作这条订阅 */
    private boolean canAccess(PtSubscriptionPlus sub) {
        if (sub == null) {
            return false;
        }
        return canAccessAll() || sub.getOwnerUserId() == null || sub.getOwnerUserId().equals(getUserId());
    }

    /**
     * 单条操作前的归属校验。
     * <p>
     * 订阅不存在与无权访问返回<b>同一句</b>提示，不区分二者：区分开就等于给了一个
     * 逐个 id 试探、枚举出别人订阅了什么的接口。
     *
     * @return 校验不通过时返回错误 Result，通过时返回 null
     */
    private <R> Result<R> denyIfInaccessible(Integer id) {
        if (canAccessAll()) {
            return null;
        }
        return canAccess(service.getById(id)) ? null : Result.error("订阅不存在或无权访问");
    }

    /** 过滤出当前用户有权操作的订阅 id，供批量接口使用 */
    private List<Integer> filterAccessible(List<Integer> ids) {
        if (canAccessAll() || ids.isEmpty()) {
            return ids;
        }
        return service.listByIds(ids).stream()
                .filter(this::canAccess)
                .map(PtSubscriptionPlus::getId)
                .toList();
    }

    @Override
    protected Wrapper<PtSubscriptionPlus> buildQueryWrapper(PtSubscriptionPlus entity) {
        LambdaQueryWrapper<PtSubscriptionPlus> wrapper = new LambdaQueryWrapper<>();
        if (!canAccessAll()) {
            Long currentUserId = getUserId();
            if (currentUserId == null) {
                // 取不到当前用户时只放行无归属的公共订阅。不能直接把 null 交给 eq()——
                // MyBatis-Plus 会生成 `owner_user_id = NULL`，在 SQL 里恒为 unknown，
                // 条件看着在、实则永远不成立，排查时极难看出来
                wrapper.isNull(PtSubscriptionPlus::getOwnerUserId);
            } else {
                wrapper.and(w -> w.eq(PtSubscriptionPlus::getOwnerUserId, currentUserId)
                        .or().isNull(PtSubscriptionPlus::getOwnerUserId));
            }
        }
        if (StringUtils.isNotBlank(entity.getTitle())) {
            wrapper.like(PtSubscriptionPlus::getTitle, entity.getTitle());
        }
        if (StringUtils.isNotBlank(entity.getMediaType())) {
            wrapper.eq(PtSubscriptionPlus::getMediaType, entity.getMediaType());
        }
        if (StringUtils.isNotBlank(entity.getStatus())) {
            wrapper.eq(PtSubscriptionPlus::getStatus, entity.getStatus());
        }
        if ("lastMatchTime".equals(entity.getSortBy())) {
            wrapper.orderByDesc(PtSubscriptionPlus::getLastMatchTime).orderByDesc(PtSubscriptionPlus::getId);
        } else {
            wrapper.orderByDesc(PtSubscriptionPlus::getId);
        }
        return wrapper;
    }

    /**
     * 查订阅详情。覆写基类实现只为补归属校验——基类按 id 直查，
     * 不校验的话非管理员可以直接 GET 到别人的订阅明细。
     */
    @Override
    @GetMapping("/{id}")
    public Result<PtSubscriptionPlus> getById(@PathVariable("id") Integer id) {
        Result<PtSubscriptionPlus> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        return super.getById(id);
    }

    /**
     * TMDb 搜索，供建订阅时选片。
     */
    @GetMapping("/tmdb-search")
    public Result<List<TmdbSearchItem>> tmdbSearch(@RequestParam("mediaType") String mediaType,
                                                   @RequestParam("keyword") String keyword) {
        return Result.success(tmdbSearchService.search(mediaType, keyword));
    }

    /**
     * 查某剧在 TMDb 上的各季集数，供选季。
     */
    @GetMapping("/tmdb-seasons/{tmdbId}")
    public Result<Integer> seasonEpisodeCount(@PathVariable("tmdbId") String tmdbId,
                                              @RequestParam("season") Integer season) {
        try {
            return Result.success(tmdbSearchService.getSeasonEpisodeCount(tmdbId, season));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 建订阅。
     */
    @PostMapping("/subscribe")
    public Result<Void> subscribe(@RequestBody SubscribeRequest request) {
        PtSubscriptionPlus sub;
        // 归属人一律以当前登录用户为准，不采信请求体：否则谁都能把订阅挂到别人名下，
        // 那个人就会收到一堆自己没订过的下载通知
        request.setOwnerUserId(getUserId());
        try {
            sub = subscriptionBiz.subscribe(request);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Service 层已做重复订阅前置校验，这里只兜并发场景下的极小概率竞态，不透出原始 SQL 错误
            log.warn("建订阅时命中唯一约束冲突（并发重复提交）：{}", e.getMessage());
            return Result.error("该作品的这一季可能已被同时提交订阅，请刷新后查看");
        } catch (Exception e) {
            log.error("建立订阅失败", e);
            return Result.error("建立订阅失败，请稍后重试");
        }
        if (SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            try {
                searchOnCreateTrigger.triggerAsync(sub.getId());
            } catch (Exception e) {
                log.warn("订阅[{}]建订阅补搜触发失败：{}", sub.getId(), e.getMessage());
            }
        }
        return Result.success();
    }

    /**
     * 查订阅进度（已入库/在途/缺集列表）。
     */
    @GetMapping("/{id}/progress")
    public Result<SubscriptionProgress> progress(@PathVariable("id") Integer id) {
        Result<SubscriptionProgress> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        try {
            return Result.success(subscriptionBiz.getProgress(id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 查订阅的每集明细。
     */
    @GetMapping("/{id}/episodes")
    public Result<List<PtSubscriptionEpisodePlus>> episodes(@PathVariable("id") Integer id) {
        Result<List<PtSubscriptionEpisodePlus>> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        return Result.success(episodeService.listBySubscription(id));
    }

    /**
     * 查订阅最近的匹配/过滤日志，供排查"这一轮为什么没抓到"。按 id 倒序，最多取 100 条。
     * <p>
     * 带上索引器名一起返回：同一个种子会被<b>多个索引器各返回一份</b>（去重键是
     * {@code (indexerId, guid)}），各家给出的做种数可能互相矛盾——一份报 0 被 {@code LOW_SEEDERS}
     * 淘汰、另一份报正数照常推送。只显示标题的话，用户看到的是"两条一模一样的记录，
     * 一条通过一条不通过"，完全无从判断差别在哪。
     * </p>
     */
    @GetMapping("/{id}/search-logs")
    public Result<List<SearchLogView>> searchLogs(@PathVariable("id") Integer id) {
        Result<List<SearchLogView>> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        List<PtSearchLogPlus> logs = searchLogService.list(new LambdaQueryWrapper<PtSearchLogPlus>()
                .eq(PtSearchLogPlus::getSubId, id)
                .orderByDesc(PtSearchLogPlus::getId)
                .last("limit 100"));
        // 一次查全量索引器建映射：日志最多 100 条但索引器通常只有个位数，
        // 逐条 getById 会打出几十次重复查询
        Map<Integer, String> indexerNames = indexerService.list().stream()
                .collect(Collectors.toMap(PtIndexerPlus::getId, PtIndexerPlus::getName, (a, b) -> a));
        return Result.success(logs.stream().map(row -> {
            SearchLogView view = new SearchLogView();
            view.setId(row.getId());
            view.setEpisode(row.getEpisode());
            view.setSource(row.getSource());
            view.setTorrentTitle(row.getTorrentTitle());
            view.setIndexerId(row.getIndexerId());
            // 索引器已被删除时留空，与下载记录页对 indexerName 的处理一致
            view.setIndexerName(row.getIndexerId() == null ? null : indexerNames.get(row.getIndexerId()));
            view.setAccepted(row.getAccepted());
            view.setReasonCode(row.getReasonCode());
            view.setReason(row.getReason());
            view.setCreateTime(row.getCreateTime());
            return view;
        }).toList());
    }

    /**
     * 立即与媒体库对账刷新。
     */
    @PostMapping("/{id}/refresh")
    public Result<Void> refresh(@PathVariable("id") Integer id) {
        Result<Void> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        try {
            subscriptionBiz.refresh(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 搜索补集：关键词并发搜索所有索引器。
     * <p>
     * 当 request.manualSelect=true 时，不自动推送最优结果，返回候选种子列表供用户挑选；
     * 否则自动推送最优结果（原逻辑保持不变）。
     * </p>
     */
    @PostMapping("/{id}/search")
    public Result<SupplementResult> search(@PathVariable("id") Integer id, @RequestBody SearchRequest request) {
        Result<SupplementResult> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        // 没有启用中的索引器时直说，别让用户拿着「0 个候选」去翻过滤规则和关键词
        if (searchSupplementService.hasNoEnabledIndexer()) {
            return Result.error("没有启用中的索引器，无法搜索。请到「PT索引器」页面添加或启用至少一个索引器");
        }
        try {
            return Result.success(searchSupplementService.supplement(id, request.getEpisode(), request.getKeyword(), request.isManualSelect()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 手动选择推送：用户在手动搜索模式选中的一个候选种子，通过本接口推送到下载器。
     */
    @PostMapping("/{id}/push-selected")
    public Result<Void> pushSelected(@PathVariable("id") Integer id, @RequestBody PushSelectedRequest request) {
        Result<Void> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        try {
            PushOutcome outcome = searchSupplementService.pushSelected(id, request.getEpisode(), request);
            if (outcome.pushed()) {
                return Result.success();
            }
            // 原因由 SubscriptionEngine 逐条路径给出，不要退回成一句笼统的猜测：
            // 「无可用缺额」与「下载器不可用」是两种不相干的故障，处置动作完全不同，
            // 而真实原因还可能是第三种（候选被过滤规则清光、被并发轮询抢先占位等）
            return Result.error("推送失败：" + outcome.reason());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 手动把某一集重置为缺失，用于用户从 Emby 误删或想重新洗版某集。
     */
    @PostMapping("/{id}/episodes/{episode}/reset")
    public Result<Void> resetEpisode(@PathVariable("id") Integer id, @PathVariable("episode") Integer episode) {
        Result<Void> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        try {
            subscriptionBiz.resetEpisode(id, episode);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 暂停订阅。
     */
    @PostMapping("/{id}/pause")
    public Result<Void> pause(@PathVariable("id") Integer id) {
        Result<Void> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        try {
            subscriptionBiz.pause(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 恢复订阅。
     */
    @PostMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable("id") Integer id) {
        Result<Void> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        try {
            subscriptionBiz.resume(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量暂停订阅，单条失败（如已被并发删除）不影响其余条目。
     */
    @PostMapping("/batchPause")
    public Result<BatchOperationResult> batchPause(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要暂停的订阅");
        }
        List<Integer> idList = filterAccessible(Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList());
        if (idList.isEmpty()) {
            return Result.error("没有可操作的订阅");
        }
        return Result.success(subscriptionBiz.pauseBatch(idList));
    }

    /**
     * 批量恢复订阅，单条失败不影响其余条目。
     */
    @PostMapping("/batchResume")
    public Result<BatchOperationResult> batchResume(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要恢复的订阅");
        }
        List<Integer> idList = filterAccessible(Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList());
        if (idList.isEmpty()) {
            return Result.error("没有可操作的订阅");
        }
        return Result.success(subscriptionBiz.resumeBatch(idList));
    }

    /**
     * 批量删除订阅，连带删除每集状态行。
     * <p>
     * 与单条 {@link #delete(Integer)} 同样的"纯 CRUD 组合"落点，用 IN 一次性执行不逐条循环。
     * </p>
     */
    @PostMapping("/batchDelete")
    public Result<Void> batchDelete(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要删除的订阅");
        }
        List<Integer> idList = filterAccessible(Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList());
        if (idList.isEmpty()) {
            return Result.error("没有可删除的订阅");
        }
        episodeService.remove(new QueryWrapper<PtSubscriptionEpisodePlus>().in("sub_id", idList));
        boolean removed = service.removeByIds(idList);
        return removed ? Result.success() : Result.error("删除失败");
    }

    /**
     * 删除订阅，连带删除其每集状态行。
     * <p>
     * 覆写基类实现：基类只删主表，会在 pt_subscription_episode 留下孤儿数据。
     * </p>
     */
    @Override
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Integer id) {
        Result<Void> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        episodeService.remove(new QueryWrapper<PtSubscriptionEpisodePlus>().eq("sub_id", id));
        boolean removed = service.removeById(id);
        return removed ? Result.success() : Result.error("删除失败");
    }
}
