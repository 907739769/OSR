package com.ruoyi.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.subscription.SearchSupplementService;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionService;
import com.ruoyi.openliststrm.pt.subscription.TmdbSearchService;
import com.ruoyi.openliststrm.pt.subscription.dto.BatchOperationResult;
import com.ruoyi.openliststrm.pt.subscription.dto.PushSelectedRequest;
import com.ruoyi.openliststrm.pt.subscription.dto.SearchRequest;
import com.ruoyi.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.ruoyi.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

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

    @Override
    protected Wrapper<PtSubscriptionPlus> buildQueryWrapper(PtSubscriptionPlus entity) {
        LambdaQueryWrapper<PtSubscriptionPlus> wrapper = new LambdaQueryWrapper<>();
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
        return Result.success(episodeService.listBySubscription(id));
    }

    /**
     * 查订阅最近的匹配/过滤日志，供排查"这一轮为什么没抓到"。按 id 倒序，最多取 100 条。
     */
    @GetMapping("/{id}/search-logs")
    public Result<List<PtSearchLogPlus>> searchLogs(@PathVariable("id") Integer id) {
        List<PtSearchLogPlus> logs = searchLogService.list(new LambdaQueryWrapper<PtSearchLogPlus>()
                .eq(PtSearchLogPlus::getSubId, id)
                .orderByDesc(PtSearchLogPlus::getId)
                .last("limit 100"));
        return Result.success(logs);
    }

    /**
     * 立即与媒体库对账刷新。
     */
    @PostMapping("/{id}/refresh")
    public Result<Void> refresh(@PathVariable("id") Integer id) {
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
        try {
            boolean pushed = searchSupplementService.pushSelected(id, request.getEpisode(), request);
            if (pushed) {
                return Result.success();
            }
            return Result.error("推送失败，可能该集已无可用缺额或下载器不可用");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 手动把某一集重置为缺失，用于用户从 Emby 误删或想重新洗版某集。
     */
    @PostMapping("/{id}/episodes/{episode}/reset")
    public Result<Void> resetEpisode(@PathVariable("id") Integer id, @PathVariable("episode") Integer episode) {
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
        List<Integer> idList = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
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
        List<Integer> idList = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
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
        List<Integer> idList = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
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
        episodeService.remove(new QueryWrapper<PtSubscriptionEpisodePlus>().eq("sub_id", id));
        boolean removed = service.removeById(id);
        return removed ? Result.success() : Result.error("删除失败");
    }
}
