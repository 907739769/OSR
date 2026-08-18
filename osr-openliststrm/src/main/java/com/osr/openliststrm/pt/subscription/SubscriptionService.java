package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.pt.calendar.TmdbEpisodeAligner;
import com.osr.openliststrm.pt.media.IMediaServerClient;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyTarget;
import com.osr.openliststrm.pt.media.MediaServerClientFactory;
import com.osr.openliststrm.pt.subscription.dto.BatchOperationResult;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.osr.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.osr.openliststrm.pt.upgrade.QualityProfile;
import com.osr.openliststrm.pt.upgrade.UpgradeState;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    public static final String STATE_UPGRADING = SubscriptionEpisodeState.UPGRADING.value();

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PAUSED = "PAUSED";

    /** 订阅级洗版开关：新建订阅一律关闭，需要洗版的订阅由用户在列表里手动打开 */
    public static final String UPGRADE_DISABLED = "0";

    /** 电影的哨兵季号与集号 */
    private static final int MOVIE_SEASON = 0;
    private static final int MOVIE_EPISODE = 0;

    /**
     * 这一集的文件是否已经在库里。UPGRADING 也算——洗版期间旧版本一直在库里可正常观看，
     * 把它算成"未入库"会让订阅从 COMPLETED 退回 ACTIVE、进度条倒退，而实际什么都没少。
     */
    private static boolean hasFileInLibrary(PtSubscriptionEpisodePlus episode) {
        return hasFileInLibrary(episode.getState());
    }

    /**
     * 同上，只按状态值判定。列表页的进度计数是聚合出来的（只有 state 和条数，没有集实体），
     * 需要这个重载才能与逐集判定共用同一条判据，而不是在聚合那侧再抄一遍
     * 「IN_LIBRARY 或 UPGRADING」。
     */
    private static boolean hasFileInLibrary(String state) {
        return STATE_IN_LIBRARY.equals(state) || STATE_UPGRADING.equals(state);
    }

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
    @Autowired
    private IPtDownloadRecordPlusService downloadRecordService;

    /**
     * 本地标题解析器。只做正则抽取，不查 TMDb、不调 AI，所以传 null 客户端即可；
     * 而且 MediaParser 不是 Spring bean（一直靠 new 管理），走注入会导致本类装配失败。
     * 用法与 {@code SubscriptionEngine} / {@code DownloadTrackService} 的同名字段一致。
     */
    private final MediaParser mediaParser = new MediaParser(null, null);

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

        List<Integer> numbers = episodeNumbers(movie, totalEpisodes);
        Map<Integer, TmdbEpisodeAligner.TmdbEpisodeRef> aligned =
                alignTmdbEpisodes(movie, request.getTmdbId(), season, numbers);
        Set<Integer> inLibrary = queryLibrary(request.getMediaType(), request.getTmdbId(), season, numbers, aligned);

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
        sub.setOwnerUserId(request.getOwnerUserId());
        // 洗版默认关闭：洗版会额外占用索引器配额与下载器带宽，且新旧版本会同时留在库里（OSR 从不删种），
        // 属于用户明确想要才该开的行为。这里显式写死，不依赖 DB 列默认值，Web/企微/TG 各入口口径一致。
        sub.setUpgradeEnabled(UPGRADE_DISABLED);
        sub.setStatus(coversAll(inLibrary, movie, totalEpisodes) ? STATUS_COMPLETED : STATUS_ACTIVE);
        subscriptionService.save(sub);

        List<PtSubscriptionEpisodePlus> episodes = new ArrayList<>();
        for (int number : numbers) {
            PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
            ep.setSubId(sub.getId());
            ep.setEpisode(number);
            ep.setState(inLibrary.contains(number) ? STATE_IN_LIBRARY : STATE_MISSING);
            applyTmdbRef(ep, aligned.get(number));
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

        Set<Integer> inLibrary = queryLibrary(sub.getMediaType(), sub.getTmdbId(), sub.getSeason(),
                episodes.stream().map(PtSubscriptionEpisodePlus::getEpisode).toList(),
                // 对账用已落库的 TMDb 集号，不再打一次 TMDb：同步任务负责保持它是新的
                episodes.stream()
                        .filter(e -> e.getTmdbEpisodeNumber() != null)
                        .collect(Collectors.toMap(PtSubscriptionEpisodePlus::getEpisode,
                                e -> new TmdbEpisodeAligner.TmdbEpisodeRef(e.getTmdbEpisodeNumber(), null),
                                (a, b) -> a)));
        List<PtSubscriptionEpisodePlus> upgraded = new ArrayList<>();
        for (PtSubscriptionEpisodePlus ep : episodes) {
            // UPGRADING 的集必须跳过：它的旧版本本来就在 Emby 里，inLibrary 恒命中，
            // 顺着走会在洗版下载还没完成时就把状态改成 IN_LIBRARY、丢掉在途标记。
            // Emby 分不出同一集的新旧版本，UPGRADING → IN_LIBRARY 只能由下载完成驱动
            // （见 DownloadTrackService#complete），不能由对账驱动。
            if (inLibrary.contains(ep.getEpisode()) && !STATE_IN_LIBRARY.equals(ep.getState())
                    && !STATE_UPGRADING.equals(ep.getState())) {
                ep.setState(STATE_IN_LIBRARY);
                applyQualityBaseline(ep);
                upgraded.add(ep);
            }
        }
        if (!upgraded.isEmpty()) {
            episodeService.updateBatchById(upgraded);
            notifyLibrarySync(sub, movie, upgraded);
        }

        boolean allInLibrary = episodes.stream().allMatch(SubscriptionService::hasFileInLibrary);
        String newStatus = allInLibrary ? STATUS_COMPLETED : STATUS_ACTIVE;
        boolean statusChanged = !newStatus.equals(sub.getStatus()) && !STATUS_PAUSED.equals(sub.getStatus());
        boolean totalChanged = totalEpisodes != sub.getTotalEpisodes();
        boolean titleChanged = refreshChineseTitle(sub);
        if (statusChanged || totalChanged || titleChanged) {
            sub.setStatus(STATUS_PAUSED.equals(sub.getStatus()) ? STATUS_PAUSED : newStatus);
            sub.setTotalEpisodes(totalEpisodes);
            subscriptionService.updateById(sub);
        }
    }

    /**
     * 存量订阅的标题补中文：早先建的订阅存的是 TMDb 详情返回的标题，缺中文翻译时那就是英文，
     * 借对账刷新顺手用别名接口把它换成中文名。
     * <p>
     * 标题本就含中文时 {@code resolveChineseTitle} 直接返回原值、不发请求；查不到中文别名同样返回原值，
     * 所以这里只在真的换出新标题时才算改动。TMDb 响应有 10 分钟进程内 + 24 小时数据库两级缓存，
     * 对账高频调用不会真的高频打 TMDb。
     * </p>
     *
     * @return 标题是否被改写
     */
    private boolean refreshChineseTitle(PtSubscriptionPlus sub) {
        try {
            String title = tmdbSearchService.resolveChineseTitle(sub.getMediaType(), sub.getTmdbId(), sub.getTitle());
            if (StringUtils.isBlank(title) || title.equals(sub.getTitle())) {
                return false;
            }
            log.info("订阅[{}] 标题补中文名：{} → {}", sub.getId(), sub.getTitle(), title);
            sub.setTitle(title);
            return true;
        } catch (Exception e) {
            log.warn("刷新订阅[{}]中文标题失败，沿用原标题：{}", sub.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 给刚入库的集写下质量基线快照，这是洗版判定的唯一依据。
     * <p>
     * 质量信息只存在于当初那个种子的标题里，靠 {@code download_id} 反查下载记录后本地解析获得。
     * {@code download_id} 为空（订阅创建时这一集就已经在 Emby 里了）或记录已被清理时，
     * OSR 不知道库里躺的是什么货色，标成 {@link UpgradeState#NO_BASELINE} 不参与洗版——
     * 盲目升级可能把好版本换成差版本，宁可不动。
     * </p>
     * <p>不抛异常：一集的画像写失败不该让整轮对账回滚，大不了这一集不参与洗版。</p>
     */
    private void applyQualityBaseline(PtSubscriptionEpisodePlus episode) {
        try {
            if (episode.getDownloadId() == null) {
                episode.setUpgradeState(UpgradeState.NO_BASELINE.value());
                return;
            }
            PtDownloadRecordPlus record = downloadRecordService.getById(episode.getDownloadId());
            if (record == null || StringUtils.isBlank(record.getTitle())) {
                episode.setUpgradeState(UpgradeState.NO_BASELINE.value());
                return;
            }
            MediaInfo info = mediaParser.parseLocal(record.getTitle());
            episode.setQuality(QualityProfile.from(info).toJson());
            // 是否已达目标质量交给洗版扫描去评估——那需要 UpgradeCriteria，
            // 对账这条路径不该为此再多读一份配置
            episode.setUpgradeState(UpgradeState.PENDING.value());
        } catch (Exception e) {
            log.warn("订阅[{}] 第{}集写质量基线失败，该集不参与洗版：{}",
                    episode.getSubId(), episode.getEpisode(), e.getMessage());
            episode.setUpgradeState(UpgradeState.NO_BASELINE.value());
        }
    }

    /** 对账检测到新入库集数时通知，电影不带集号列表，剧集列出本轮具体新入库的集号 */
    private void notifyLibrarySync(PtSubscriptionPlus sub, boolean movie, List<PtSubscriptionEpisodePlus> upgraded) {
        String detail = movie ? "" : " S" + sub.getSeason() + " 第 " + upgraded.stream()
                .map(PtSubscriptionEpisodePlus::getEpisode)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining("、")) + " 集";
        notifySafely(NotificationType.EMBY_LIBRARY_SYNC, "📀 已入库：《" + StringUtils.escapeHtml(sub.getTitle()) + "》" + detail, sub);
    }

    /** 发通知但绝不让通知失败影响主流程（单测环境下 SpringUtils.getBean 会抛异常，这里兜住） */
    private void notifySafely(NotificationType type, String msg, PtSubscriptionPlus sub) {
        try {
            TgHelper.sendMsg(type, msg, NotifyTarget.owner(sub == null ? null : sub.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
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
        progress.setInLibraryCount((int) episodes.stream().filter(SubscriptionService::hasFileInLibrary).count());
        progress.setInFlightCount((int) episodes.stream().filter(e -> STATE_IN_FLIGHT.equals(e.getState())).count());
        progress.setMissingEpisodes(episodes.stream()
                .filter(e -> STATE_MISSING.equals(e.getState()))
                .map(PtSubscriptionEpisodePlus::getEpisode)
                .sorted()
                .toList());
        return progress;
    }

    /**
     * 给列表页的订阅批量填上进度计数（已入库/在途/缺失），让卡片能直接显示「12/26」。
     * <p>
     * 在此之前，「这部还缺几集」——用户打开这个页面的头号问题——只能逐条点开进度弹窗，
     * 一页几十条就是几十次点击加几十个单条请求。
     * </p>
     * <p>
     * 口径与 {@link #getProgress} 完全一致，共用 {@link #hasFileInLibrary(String)}：
     * 卡片显示「12/26」而点开弹窗是「11/26」，比不显示更糟。
     * </p>
     * <p>
     * 整批只发一条聚合 SQL。查不到集记录的订阅（刚建好、集表还没铺开）三项都填 0 而不是
     * 留 null，前端就不必为「字段缺失」和「确实是 0」分两条渲染路径。
     * </p>
     */
    public void fillProgressCounts(List<PtSubscriptionPlus> subs) {
        if (subs == null || subs.isEmpty()) {
            return;
        }
        List<Integer> ids = subs.stream().map(PtSubscriptionPlus::getId).filter(java.util.Objects::nonNull).toList();
        Map<Integer, Map<String, Integer>> grouped = episodeService.countStatesBySubscriptions(ids);
        for (PtSubscriptionPlus sub : subs) {
            Map<String, Integer> counts = grouped.getOrDefault(sub.getId(), Collections.emptyMap());
            int inLibrary = 0;
            int inFlight = 0;
            int missing = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String state = entry.getKey();
                int count = entry.getValue();
                if (hasFileInLibrary(state)) {
                    inLibrary += count;
                } else if (STATE_IN_FLIGHT.equals(state)) {
                    inFlight += count;
                } else if (STATE_MISSING.equals(state)) {
                    missing += count;
                }
            }
            sub.setInLibraryCount(inLibrary);
            sub.setInFlightCount(inFlight);
            sub.setMissingCount(missing);
        }
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
    /**
     * 查媒体库里这部剧已有哪些集，返回<b>本地集号</b>的集合。
     * <p>
     * 难点在于本地集号与媒体库里的集号不一定是同一套。媒体库按刮削结果组织，
     * 而长篇动画常被整部平铺在第 1 季、用绝对集号编号（实测用户的 Emby 里
     * 航海王 1172 集全在 Season 1，第 23 季查出来是空的，导致 26 集里 11 集
     * 明明下载完成却一直卡在 MISSING）。因此按三条规则依次判定，命中任一即算入库：
     * </p>
     * <ol>
     *   <li>本季里有该<b>本地集号</b> —— 普通剧集走这条，行为与改动前一致</li>
     *   <li>本季里有该集的 <b>TMDb 集号</b> —— 库按 TMDb 编号刮削时走这条</li>
     *   <li>整部剧的任意季里有该集的 <b>TMDb 集号</b> —— 绝对编号平铺的动画库走这条</li>
     * </ol>
     * <p>
     * 第 3 条<b>仅在 TMDb 集号与本地集号不同时启用</b>。两者相同说明这部剧本来就是
     * 常规编号，前两条已经够用；此时若再放开全剧匹配，第 2 季第 17 集会把第 1 季第 17 集
     * 误判成已入库。而航海王的 1168 与 13 差得足够远，不存在这种碰撞。
     * </p>
     */
    private Set<Integer> queryLibrary(String mediaType, String tmdbId, int season,
                                      List<Integer> localEpisodes,
                                      Map<Integer, TmdbEpisodeAligner.TmdbEpisodeRef> aligned) {
        PtMediaServerPlus server = mediaServerService.getActive();
        if (server == null) {
            log.warn("未配置启用中的媒体服务器，订阅 {} 的已入库集数按 0 处理", tmdbId);
            return Collections.emptySet();
        }
        try {
            IMediaServerClient client = mediaServerClientFactory.get(server);
            if (TYPE_MOVIE.equalsIgnoreCase(mediaType)) {
                return client.hasMovie(server, tmdbId) ? Set.of(MOVIE_EPISODE) : Collections.emptySet();
            }

            Set<Integer> inSeason = client.listEpisodes(server, tmdbId, season);
            Set<Integer> result = new HashSet<>();
            Set<Integer> wholeSeries = null;

            for (Integer episode : localEpisodes) {
                if (episode == null) {
                    continue;
                }
                if (inSeason.contains(episode)) {
                    result.add(episode);
                    continue;
                }
                TmdbEpisodeAligner.TmdbEpisodeRef ref = aligned.get(episode);
                if (ref == null || ref.episodeNumber() == episode) {
                    continue;
                }
                if (inSeason.contains(ref.episodeNumber())) {
                    result.add(episode);
                    continue;
                }
                // 整部剧的编号只在确有需要时拉一次，普通剧集根本走不到这里
                if (wholeSeries == null) {
                    wholeSeries = client.listAllEpisodeNumbers(server, tmdbId);
                }
                if (wholeSeries.contains(ref.episodeNumber())) {
                    result.add(episode);
                }
            }
            return result;
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
        // 对齐要拿整季的集号（1..totalEpisodes）而不只是新增的那几集：
        // 位置兜底靠「两边集数相等」判断，只传新增集会让集数对不上，兜底永远不启用
        List<Integer> allNumbers = episodeNumbers(false, totalEpisodes);
        Map<Integer, TmdbEpisodeAligner.TmdbEpisodeRef> aligned =
                alignTmdbEpisodes(false, sub.getTmdbId(), sub.getSeason(), allNumbers);
        List<PtSubscriptionEpisodePlus> added = new ArrayList<>();
        for (int number = maxExisting + 1; number <= totalEpisodes; number++) {
            PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
            ep.setSubId(sub.getId());
            ep.setEpisode(number);
            ep.setState(STATE_MISSING);
            applyTmdbRef(ep, aligned.get(number));
            added.add(ep);
        }
        episodeService.saveBatch(added);
        existing.addAll(added);
        log.info("订阅[{}] 总集数由 {} 增至 {}，已补齐 {} 个新集", sub.getId(), maxExisting, totalEpisodes, added.size());
    }

    /**
     * 取该季的播出日期表，供建订阅/补集时填 air_date。
     * <p>
     * 取不到只影响日历排格，不该让订阅本身建不起来——因此吞掉异常返回空表，
     * 剩下的交给 {@code EpisodeAirDateSyncTask} 下一轮补。电影的上映日期不走这里
     * （季端点是 TV 专用），留给同步任务从详情里取。
     * </p>
     */
    private Map<Integer, TmdbEpisodeAligner.TmdbEpisodeRef> alignTmdbEpisodes(
            boolean movie, String tmdbId, Integer season, List<Integer> localEpisodes) {
        if (movie || tmdbId == null || season == null) {
            return Map.of();
        }
        try {
            return TmdbEpisodeAligner.align(localEpisodes, tmdbSearchService.getSeasonEpisodeAirDates(tmdbId, season));
        } catch (Exception e) {
            log.warn("取剧集 {} 第 {} 季的播出日期失败，本次留空待同步任务补齐：{}", tmdbId, season, e.getMessage());
            return Map.of();
        }
    }

    /** 把对齐结果写进集行；对不上时两个字段都留空，等同步任务下一轮再补 */
    private static void applyTmdbRef(PtSubscriptionEpisodePlus ep, TmdbEpisodeAligner.TmdbEpisodeRef ref) {
        if (ref == null) {
            return;
        }
        ep.setTmdbEpisodeNumber(ref.episodeNumber());
        ep.setAirDate(toDate(ref.airDate()));
    }

    private static Date toDate(LocalDate date) {
        return date == null ? null : Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
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
