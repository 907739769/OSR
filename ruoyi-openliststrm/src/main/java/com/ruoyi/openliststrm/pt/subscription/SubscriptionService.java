package com.ruoyi.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.media.MediaServerClientFactory;
import com.ruoyi.openliststrm.pt.subscription.dto.BatchOperationResult;
import com.ruoyi.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.ruoyi.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.ruoyi.openliststrm.pt.subscription.dto.TmdbSearchItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 订阅的建立、对账与状态维护。
 *
 * @author Jack
 */
@Slf4j
@Service
public class SubscriptionService {

    /** 媒体类型：电影。判断是否电影只看它，绝不能用 season == 0（剧集特别篇也是第 0 季） */
    public static final String TYPE_MOVIE = "MOVIE";

    public static final String STATE_MISSING = SubscriptionEpisodeState.MISSING.value();
    public static final String STATE_IN_FLIGHT = SubscriptionEpisodeState.IN_FLIGHT.value();
    public static final String STATE_IN_LIBRARY = SubscriptionEpisodeState.IN_LIBRARY.value();

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PAUSED = "PAUSED";

    /** 电影的哨兵季号与集号 */
    private static final int MOVIE_SEASON = 0;
    private static final int MOVIE_EPISODE = 0;

    @Autowired
    private IPtSubscriptionPlusService subscriptionService;
    @Autowired
    private IPtSubscriptionEpisodePlusService episodeService;
    @Autowired
    private IPtMediaServerPlusService mediaServerService;
    @Autowired
    private MediaServerClientFactory mediaServerClientFactory;
    @Autowired
    private TmdbSearchService tmdbSearchService;

    /**
     * 建订阅：查 TMDb 拿元信息与总集数 → 落库 → 生成每集行 → 立即查一次 Emby 初始化状态。
     * <p>
     * 因为最后一步，订阅创建完就能显示准确的「已入库 5/12」，不必等首次轮询。
     * Emby 不可用（未配置或查询失败）不阻断建订阅，按全部缺失处理。
     * </p>
     *
     * @throws IllegalArgumentException 入参非法，或 TMDb 查不到该作品
     */
    @Transactional
    public PtSubscriptionPlus subscribe(SubscribeRequest request) {
        if (StringUtils.isBlank(request.getTmdbId())) {
            throw new IllegalArgumentException("tmdbId 不能为空");
        }
        boolean movie = TYPE_MOVIE.equalsIgnoreCase(request.getMediaType());
        if (!movie && request.getSeason() == null) {
            throw new IllegalArgumentException("订阅剧集必须指定季号");
        }

        int season = movie ? MOVIE_SEASON : request.getSeason();
        String mediaType = movie ? TYPE_MOVIE : "TV";
        boolean duplicated = subscriptionService.exists(new LambdaQueryWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getTmdbId, request.getTmdbId())
                .eq(PtSubscriptionPlus::getMediaType, mediaType)
                .eq(PtSubscriptionPlus::getSeason, season));
        if (duplicated) {
            throw new IllegalArgumentException(movie ? "该电影已订阅过，请勿重复订阅"
                    : "该剧集第" + season + "季已订阅过，请勿重复订阅");
        }

        TmdbSearchItem detail;
        int totalEpisodes;
        try {
            detail = tmdbSearchService.getDetail(request.getMediaType(), request.getTmdbId());
            totalEpisodes = movie ? 1 : tmdbSearchService.getSeasonEpisodeCount(request.getTmdbId(), season);
        } catch (IllegalArgumentException e) {
            // TMDbApiService 内部已对网络异常/5xx/429 做过3次指数退避重试，这里拿到的已经是最终结果，
            // 再重试一次没有意义。唯一能改善的是让用户分得清"服务暂时不可用"和"ID/季号真的不对"。
            throw new IllegalArgumentException(
                    "获取 TMDb 详情失败（TMDb 服务可能暂时不可用，或该 ID/季号有误），请稍后重试：" + e.getMessage(), e);
        }

        Set<Integer> inLibrary = queryLibrary(request.getMediaType(), request.getTmdbId(), season);

        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setTmdbId(request.getTmdbId());
        sub.setMediaType(mediaType);
        sub.setTitle(detail.getTitle());
        sub.setOriginalTitle(detail.getOriginalTitle());
        sub.setEnglishTitle(detail.getEnglishTitle());
        sub.setImdbId(detail.getImdbId());
        sub.setYear(detail.getYear());
        sub.setSeason(season);
        sub.setTotalEpisodes(totalEpisodes);
        sub.setPosterPath(detail.getPosterPath());
        sub.setDownloaderId(request.getDownloaderId());
        sub.setFilterOverride(request.getFilterOverride());
        sub.setStatus(coversAll(inLibrary, movie, totalEpisodes) ? STATUS_COMPLETED : STATUS_ACTIVE);
        subscriptionService.save(sub);

        List<PtSubscriptionEpisodePlus> episodes = new ArrayList<>();
        for (int number : episodeNumbers(movie, totalEpisodes)) {
            PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
            ep.setSubId(sub.getId());
            ep.setEpisode(number);
            ep.setState(inLibrary.contains(number) ? STATE_IN_LIBRARY : STATE_MISSING);
            episodes.add(ep);
        }
        episodeService.saveBatch(episodes);

        log.info("已建立订阅[{}] {} 共{}集，其中已入库{}集", sub.getId(), sub.getTitle(), totalEpisodes, inLibrary.size());
        return sub;
    }

    /**
     * 对账刷新：重新拉 TMDb 总集数补齐集行 → 查 Emby → 推进集状态 → 重算订阅状态。
     * <p>
     * <b>只升级不降级</b>：MISSING / IN_FLIGHT → IN_LIBRARY 会做，反向不做。
     * 若用户从 Emby 删了某集，不把它退回 MISSING——否则一次误删会触发一轮重新下载。
     * 代价是进度显示偏乐观，这是有意的取舍。
     * </p>
     *
     * @throws IllegalArgumentException 订阅不存在
     */
    @Transactional
    public void refresh(Integer subId) {
        PtSubscriptionPlus sub = requireSubscription(subId);
        boolean movie = TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
        // listBySubscription 的返回值不保证可变（测试里就是 List.of 的不可变列表），
        // appendNewEpisodes 需要往里 addAll，这里先做一份可变副本
        List<PtSubscriptionEpisodePlus> episodes = new ArrayList<>(episodeService.listBySubscription(subId));

        int totalEpisodes = sub.getTotalEpisodes();
        if (!movie) {
            // 连载剧的总集数会增长，TMDb 对正在播出的季常先给一个偏小的值
            try {
                totalEpisodes = tmdbSearchService.getSeasonEpisodeCount(sub.getTmdbId(), sub.getSeason());
            } catch (Exception e) {
                log.warn("刷新订阅[{}]时取 TMDb 总集数失败，沿用原值 {}：{}", subId, totalEpisodes, e.getMessage());
            }
            appendNewEpisodes(sub, episodes, totalEpisodes);
        }

        Set<Integer> inLibrary = queryLibrary(sub.getMediaType(), sub.getTmdbId(), sub.getSeason());
        List<PtSubscriptionEpisodePlus> upgraded = new ArrayList<>();
        for (PtSubscriptionEpisodePlus ep : episodes) {
            if (inLibrary.contains(ep.getEpisode()) && !STATE_IN_LIBRARY.equals(ep.getState())) {
                ep.setState(STATE_IN_LIBRARY);
                upgraded.add(ep);
            }
        }
        if (!upgraded.isEmpty()) {
            episodeService.updateBatchById(upgraded);
        }

        boolean allInLibrary = episodes.stream().allMatch(e -> STATE_IN_LIBRARY.equals(e.getState()));
        String newStatus = allInLibrary ? STATUS_COMPLETED : STATUS_ACTIVE;
        boolean statusChanged = !newStatus.equals(sub.getStatus()) && !STATUS_PAUSED.equals(sub.getStatus());
        boolean totalChanged = totalEpisodes != sub.getTotalEpisodes();
        if (statusChanged || totalChanged) {
            sub.setStatus(STATUS_PAUSED.equals(sub.getStatus()) ? STATUS_PAUSED : newStatus);
            sub.setTotalEpisodes(totalEpisodes);
            subscriptionService.updateById(sub);
        }
    }

    /**
     * 查进度，供前端展示「已入库 N/M，缺第 X、Y 集」。
     *
     * @throws IllegalArgumentException 订阅不存在
     */
    public SubscriptionProgress getProgress(Integer subId) {
        PtSubscriptionPlus sub = requireSubscription(subId);
        List<PtSubscriptionEpisodePlus> episodes = episodeService.listBySubscription(subId);

        SubscriptionProgress progress = new SubscriptionProgress();
        progress.setSubId(sub.getId());
        progress.setTitle(sub.getTitle());
        progress.setStatus(sub.getStatus());
        progress.setTotalEpisodes(sub.getTotalEpisodes() == null ? episodes.size() : sub.getTotalEpisodes());
        progress.setInLibraryCount((int) episodes.stream().filter(e -> STATE_IN_LIBRARY.equals(e.getState())).count());
        progress.setInFlightCount((int) episodes.stream().filter(e -> STATE_IN_FLIGHT.equals(e.getState())).count());
        progress.setMissingEpisodes(episodes.stream()
                .filter(e -> STATE_MISSING.equals(e.getState()))
                .map(PtSubscriptionEpisodePlus::getEpisode)
                .sorted()
                .toList());
        return progress;
    }

    /** 暂停订阅，暂停期间不参与 RSS 匹配 */
    public void pause(Integer subId) {
        PtSubscriptionPlus sub = requireSubscription(subId);
        sub.setStatus(STATUS_PAUSED);
        subscriptionService.updateById(sub);
    }

    /** 恢复订阅 */
    public void resume(Integer subId) {
        PtSubscriptionPlus sub = requireSubscription(subId);
        sub.setStatus(STATUS_ACTIVE);
        subscriptionService.updateById(sub);
    }

    /** 批量暂停：逐条复用单条 pause，一条失败（如已被并发删除）不影响其余条目 */
    public BatchOperationResult pauseBatch(List<Integer> ids) {
        int success = 0;
        List<Integer> failed = new ArrayList<>();
        for (Integer id : ids) {
            try {
                pause(id);
                success++;
            } catch (IllegalArgumentException e) {
                failed.add(id);
            }
        }
        return new BatchOperationResult(success, failed);
    }

    /** 批量恢复：逐条复用单条 resume，一条失败不影响其余条目 */
    public BatchOperationResult resumeBatch(List<Integer> ids) {
        int success = 0;
        List<Integer> failed = new ArrayList<>();
        for (Integer id : ids) {
            try {
                resume(id);
                success++;
            } catch (IllegalArgumentException e) {
                failed.add(id);
            }
        }
        return new BatchOperationResult(success, failed);
    }

    /**
     * 手动把某一集重置为 MISSING。
     * <p>
     * {@link #refresh} 是"只升级不降级"的对账逻辑，这里是打破它的显式人工出口——
     * 用户从 Emby 误删某集，或想让某集重新走一轮下载（洗版）时使用。
     * </p>
     *
     * @throws IllegalArgumentException 订阅或该集不存在
     */
    @Transactional
    public void resetEpisode(Integer subId, Integer episode) {
        requireSubscription(subId);
        PtSubscriptionEpisodePlus ep = episodeService.getOne(new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                .eq(PtSubscriptionEpisodePlus::getSubId, subId)
                .eq(PtSubscriptionEpisodePlus::getEpisode, episode));
        if (ep == null) {
            throw new IllegalArgumentException("集不存在：sub=" + subId + " episode=" + episode);
        }
        ep.setState(STATE_MISSING);
        ep.setFailCount(0);
        ep.setDownloadId(null);
        episodeService.updateById(ep);
        log.info("订阅[{}] 第{}集已手动重置为缺失", subId, episode);
    }

    // ---------- 内部 ----------

    private PtSubscriptionPlus requireSubscription(Integer subId) {
        PtSubscriptionPlus sub = subscriptionService.getById(subId);
        if (sub == null) {
            throw new IllegalArgumentException("订阅不存在：" + subId);
        }
        return sub;
    }

    /**
     * 查媒体库中已有的集号。电影用集号 0 表示"已在库"。
     * <p>
     * 未配置媒体服务器或查询失败时返回空集合并记 warn——媒体服务器是加分项不是前置依赖。
     * </p>
     */
    private Set<Integer> queryLibrary(String mediaType, String tmdbId, int season) {
        PtMediaServerPlus server = mediaServerService.getActive();
        if (server == null) {
            log.warn("未配置启用中的媒体服务器，订阅 {} 的已入库集数按 0 处理", tmdbId);
            return Collections.emptySet();
        }
        try {
            if (TYPE_MOVIE.equalsIgnoreCase(mediaType)) {
                return mediaServerClientFactory.get(server).hasMovie(server, tmdbId)
                        ? Set.of(MOVIE_EPISODE) : Collections.emptySet();
            }
            return mediaServerClientFactory.get(server).listEpisodes(server, tmdbId, season);
        } catch (Exception e) {
            log.warn("查询媒体库失败，订阅 {} 的已入库集数按 0 处理：{}", tmdbId, e.getMessage());
            return Collections.emptySet();
        }
    }

    /** 总集数增长时补齐新集行，新集一律 MISSING */
    private void appendNewEpisodes(PtSubscriptionPlus sub, List<PtSubscriptionEpisodePlus> existing, int totalEpisodes) {
        int maxExisting = existing.stream().mapToInt(PtSubscriptionEpisodePlus::getEpisode).max().orElse(0);
        if (totalEpisodes <= maxExisting) {
            return;
        }
        List<PtSubscriptionEpisodePlus> added = new ArrayList<>();
        for (int number = maxExisting + 1; number <= totalEpisodes; number++) {
            PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
            ep.setSubId(sub.getId());
            ep.setEpisode(number);
            ep.setState(STATE_MISSING);
            added.add(ep);
        }
        episodeService.saveBatch(added);
        existing.addAll(added);
        log.info("订阅[{}] 总集数由 {} 增至 {}，已补齐 {} 个新集", sub.getId(), maxExisting, totalEpisodes, added.size());
    }

    private List<Integer> episodeNumbers(boolean movie, int totalEpisodes) {
        if (movie) {
            return List.of(MOVIE_EPISODE);
        }
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i <= totalEpisodes; i++) {
            numbers.add(i);
        }
        return numbers;
    }

    private boolean coversAll(Set<Integer> inLibrary, boolean movie, int totalEpisodes) {
        return inLibrary.containsAll(episodeNumbers(movie, totalEpisodes));
    }
}
