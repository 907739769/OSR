package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.core.domain.PageResult;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.subscription.SubscriptionMatcher;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.dto.SupplementResult;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.task.dto.BatchRetryResult;
import com.osr.openliststrm.pt.task.dto.DownloadRecordView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 下载记录管理台：列表展示的名称拼接 + 失败记录的手动重试。
 * 与 {@link DownloadTrackService} 分开——那个是自动轮询编排，这个是面向管理界面的读操作与人工干预。
 *
 * @author Jack
 */
@Slf4j
@Service
public class DownloadRecordAdminService {

    private static final String STATE_FAILED = DownloadRecordState.FAILED.value();
    private static final String EP_STATE_BLOCKED = SubscriptionEpisodeState.BLOCKED.value();
    private static final String EP_STATE_MISSING = SubscriptionEpisodeState.MISSING.value();
    /** {@code pt_download_record.fail_ignored} 的「已忽略」取值 */
    public static final String FAIL_IGNORED = "1";
    private static final String FAIL_NOT_IGNORED = "0";

    /** 清理旧记录允许的保留天数。下限 30 天：再短就会碰到还在对账、还在保种的那批 */
    public static final List<Integer> CLEANUP_ALLOWED_DAYS = List.of(30, 90, 180, 365);

    private final IPtDownloadRecordPlusService recordService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtIndexerPlusService indexerService;
    private final IPtDownloaderPlusService downloaderService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final SearchSupplementService searchSupplementService;
    private final IPtTorrentBlacklistPlusService blacklistService;

    public DownloadRecordAdminService(IPtDownloadRecordPlusService recordService,
                                      IPtSubscriptionPlusService subscriptionService,
                                      IPtIndexerPlusService indexerService,
                                      IPtDownloaderPlusService downloaderService,
                                      IPtSubscriptionEpisodePlusService episodeService,
                                      SearchSupplementService searchSupplementService,
                                      IPtTorrentBlacklistPlusService blacklistService) {
        this.recordService = recordService;
        this.subscriptionService = subscriptionService;
        this.indexerService = indexerService;
        this.downloaderService = downloaderService;
        this.episodeService = episodeService;
        this.searchSupplementService = searchSupplementService;
        this.blacklistService = blacklistService;
    }

    // ---------- 归属 ----------

    /**
     * 当前用户能否操作这条下载记录。下载记录没有自己的归属列，归属跟着订阅走，
     * 判据与订阅页 {@code PtSubscriptionRestController#canAccess}、统计面板 {@link PtStatsScope} 同一条：
     * 管理员全可见，其余用户只能碰自己的订阅与无归属的公共订阅。
     * <p>
     * <b>订阅已删除的记录只有管理员可见</b>——没有订阅就无从判断它曾经属于谁，
     * 与列表的 {@code sub_id IN (可见订阅)} 条件口径一致。
     * </p>
     */
    public boolean canAccess(Integer recordId, PtStatsScope scope) {
        if (scope.all()) {
            return true;
        }
        PtDownloadRecordPlus record = recordService.getById(recordId);
        if (record == null) {
            return false;
        }
        return canAccessSub(subscriptionService.getById(record.getSubId()), scope);
    }

    /** 批量接口用：从 ids 里筛出当前用户有权操作的那部分，保持原顺序 */
    public List<Integer> filterAccessible(List<Integer> ids, PtStatsScope scope) {
        if (scope.all() || ids.isEmpty()) {
            return ids;
        }
        Map<Integer, Integer> subIdOf = recordService.listByIds(ids).stream()
                .collect(Collectors.toMap(PtDownloadRecordPlus::getId, PtDownloadRecordPlus::getSubId, (a, b) -> a));
        if (subIdOf.isEmpty()) {
            return List.of();
        }
        Set<Integer> accessibleSubs = subscriptionService.listByIds(new HashSet<>(subIdOf.values())).stream()
                .filter(sub -> canAccessSub(sub, scope))
                .map(PtSubscriptionPlus::getId)
                .collect(Collectors.toSet());
        return ids.stream()
                .filter(id -> accessibleSubs.contains(subIdOf.get(id)))
                .toList();
    }

    private boolean canAccessSub(PtSubscriptionPlus sub, PtStatsScope scope) {
        if (sub == null) {
            return false;
        }
        return sub.getOwnerUserId() == null || sub.getOwnerUserId().equals(scope.userId());
    }

    /**
     * 把分页查出的原始记录批量补上订阅/索引器/下载器的展示名。
     * 关联对象已被删除（订阅删除、索引器/下载器被删）时对应名称字段为 null，不阻断整行展示。
     */
    public PageResult<DownloadRecordView> enrich(PageResult<PtDownloadRecordPlus> page) {
        List<PtDownloadRecordPlus> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(List.of(), page.getTotal(), page.getPage(), page.getSize());
        }
        Map<Integer, PtSubscriptionPlus> subs = subscriptionService.listByIds(
                        records.stream().map(PtDownloadRecordPlus::getSubId).distinct().toList())
                .stream().collect(Collectors.toMap(PtSubscriptionPlus::getId, s -> s));
        Map<Integer, PtIndexerPlus> indexers = indexerService.listByIds(
                        records.stream().map(PtDownloadRecordPlus::getIndexerId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(PtIndexerPlus::getId, i -> i));
        Map<Integer, PtDownloaderPlus> downloaders = downloaderService.listByIds(
                        records.stream().map(PtDownloadRecordPlus::getDownloaderId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(PtDownloaderPlus::getId, d -> d));

        List<DownloadRecordView> views = records.stream()
                .map(r -> toView(r, subs.get(r.getSubId()), indexers.get(r.getIndexerId()), downloaders.get(r.getDownloaderId())))
                .toList();
        markBlacklisted(views, records);
        markSuperseded(views, records);
        return PageResult.of(views, page.getTotal(), page.getPage(), page.getSize());
    }

    /**
     * 标出本页每条记录的种子 / 发布组是否已被拉黑。两个维度各一条 IN 查询，不逐条查。
     * 发布组用的是与拉黑按钮同一套本地解析（{@link IPtTorrentBlacklistPlusService#releaseGroupOf}），
     * 否则会出现「页面说没拉黑、点了却提示已在黑名单中」的对不上。
     */
    private void markBlacklisted(List<DownloadRecordView> views, List<PtDownloadRecordPlus> records) {
        Map<Integer, String> guidHashOf = records.stream()
                .filter(r -> r.getGuidHash() != null)
                .collect(Collectors.toMap(PtDownloadRecordPlus::getId, PtDownloadRecordPlus::getGuidHash, (a, b) -> a));
        Set<String> blockedGuids = blacklistService.findBlockedValues(
                PtTorrentBlacklistPlus.TYPE_GUID, new HashSet<>(guidHashOf.values()));

        for (DownloadRecordView view : views) {
            view.setReleaseGroup(blacklistService.releaseGroupOf(view.getTitle()));
        }
        Set<String> groups = views.stream()
                .map(DownloadRecordView::getReleaseGroup)
                .filter(Objects::nonNull)
                .map(blacklistService::normalizeReleaseGroup)
                .collect(Collectors.toSet());
        Set<String> blockedGroups = blacklistService.findBlockedValues(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP, groups);

        for (DownloadRecordView view : views) {
            String hash = guidHashOf.get(view.getId());
            view.setGuidBlacklisted(hash != null && blockedGuids.contains(hash));
            String group = view.getReleaseGroup();
            view.setReleaseGroupBlacklisted(group != null
                    && blockedGroups.contains(blacklistService.normalizeReleaseGroup(group)));
        }
    }

    /**
     * 给本页的 FAILED 记录找「接替者」：同一订阅、id 更大、覆盖了同一集的下载记录。
     * <p>
     * 重试与自动补搜成功时都是<b>新建</b>一条记录，失败的那条原样留着。不标出来的话，
     * 用户看到一条挂着「立即重试」的失败记录，会以为这一集还没着落而再点一次——
     * 实际上后面那条可能早就下完了。只查本页失败记录涉及的订阅、且只取 id 更大的行，
     * 扫描范围很小。
     * </p>
     * <p>
     * 覆盖判定：失败的是季包时只认后续的季包；失败的是单集时，后续单集 / 区间 / 季包覆盖到它都算。
     * 失败的季包被后续逐集补齐的情况<b>不算</b>——拿不准是不是整季都补上了，宁可留着重试按钮。
     * </p>
     * <p>
     * 同一条规则的 SQL 写法在 {@link UnresolvedFailureSql}（统计与「隐藏已接替」筛选用），改这里必须同步改那边。
     * </p>
     */
    private void markSuperseded(List<DownloadRecordView> views, List<PtDownloadRecordPlus> records) {
        List<PtDownloadRecordPlus> failed = records.stream()
                .filter(r -> STATE_FAILED.equals(r.getState()))
                .toList();
        if (failed.isEmpty()) {
            return;
        }
        int minId = failed.stream().mapToInt(PtDownloadRecordPlus::getId).min().orElseThrow();
        List<Integer> subIds = failed.stream().map(PtDownloadRecordPlus::getSubId).distinct().toList();
        List<PtDownloadRecordPlus> later = new ArrayList<>(recordService.list(new QueryWrapper<PtDownloadRecordPlus>()
                .select("id", "sub_id", "episode", "episode_end")
                .in("sub_id", subIds)
                .gt("id", minId)));
        later.sort(Comparator.comparing(PtDownloadRecordPlus::getId));

        Map<Integer, DownloadRecordView> viewById = views.stream()
                .collect(Collectors.toMap(DownloadRecordView::getId, v -> v, (a, b) -> a));
        for (PtDownloadRecordPlus f : failed) {
            later.stream()
                    .filter(n -> n.getId() > f.getId() && Objects.equals(n.getSubId(), f.getSubId()) && covers(n, f.getEpisode()))
                    .findFirst()
                    .ifPresent(n -> viewById.get(f.getId()).setSupersededById(n.getId()));
        }
    }

    /** 下载记录 {@code r} 是否覆盖了集号 {@code episode}（季包哨兵值见 {@link SubscriptionMatcher#SEASON_PACK}） */
    private static boolean covers(PtDownloadRecordPlus r, int episode) {
        if (r.getEpisode() == SubscriptionMatcher.SEASON_PACK) {
            return true;
        }
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return false;
        }
        int end = r.getEpisodeEnd() != null && r.getEpisodeEnd() > r.getEpisode() ? r.getEpisodeEnd() : r.getEpisode();
        return r.getEpisode() <= episode && episode <= end;
    }

    private DownloadRecordView toView(PtDownloadRecordPlus r, PtSubscriptionPlus sub,
                                      PtIndexerPlus indexer, PtDownloaderPlus downloader) {
        DownloadRecordView view = new DownloadRecordView();
        view.setId(r.getId());
        view.setSubId(r.getSubId());
        view.setSubTitle(sub == null ? null : sub.getTitle());
        view.setEpisodeLabel(episodeLabel(sub, r.getEpisode(), r.getEpisodeEnd()));
        view.setIndexerId(r.getIndexerId());
        view.setIndexerName(indexer == null ? null : indexer.getName());
        view.setDownloaderId(r.getDownloaderId());
        view.setDownloaderName(downloader == null ? null : downloader.getName());
        view.setTitle(r.getTitle());
        view.setTorrentHash(r.getTorrentHash());
        view.setSize(r.getSize());
        view.setSeeders(r.getSeeders());
        view.setState(r.getState());
        view.setProgress(r.getProgress());
        view.setFailReason(r.getFailReason());
        view.setFailReasonCode(r.getFailReasonCode());
        view.setFailIgnored(FAIL_IGNORED.equals(r.getFailIgnored()));
        view.setPushedTime(r.getPushedTime());
        view.setCompletedTime(r.getCompletedTime());
        view.setHrState(r.getHrState());
        view.setHrSeedSeconds(r.getHrSeedSeconds());
        view.setHrRatio(r.getHrRatio());
        // 把站点要求一并带出去，前端才算得出"还差多久"；索引器已被删除时留空，
        // 前端只显示当前进度不显示目标，与 indexerName 为 null 时的处理一致
        if (indexer != null) {
            view.setHrSeedHoursRequired(indexer.getHrSeedHours());
            view.setHrRatioRequired(indexer.getHrRatio());
        }
        return view;
    }

    private String episodeLabel(PtSubscriptionPlus sub, int episode, Integer episodeEnd) {
        if (sub != null && SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return "电影";
        }
        Integer season = sub == null ? null : sub.getSeason();
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return seasonCode(season) + " 季包";
        }
        if (episodeEnd != null && episodeEnd > episode) {
            return episodeCode(season, episode) + "-E" + pad(episodeEnd);
        }
        return episodeCode(season, episode);
    }

    /**
     * 手动重试一条失败的下载记录：按订阅标题 + 季/集号拼出关键词，走与"搜索补齐"相同的三级回退链路。
     * <p>
     * 若关联集已因连续失败达到熔断阈值转为 BLOCKED，搜索补齐内部的占位逻辑只认 MISSING 状态，
     * 直接重试会静默占位失败；这里先把该记录对应的 BLOCKED 集重置回 MISSING 并清零失败计数，
     * 相当于人工重新给一次机会。
     * </p>
     *
     * @throws IllegalArgumentException 记录不存在、记录不是 FAILED 状态、关联订阅不存在或未在订阅中
     */
    public SupplementResult retry(Integer recordId) {
        PtDownloadRecordPlus record = recordService.getById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("下载记录不存在：" + recordId);
        }
        if (!STATE_FAILED.equals(record.getState())) {
            throw new IllegalArgumentException("只有失败的下载记录才能重试，当前状态：" + record.getState());
        }
        PtSubscriptionPlus sub = subscriptionService.getById(record.getSubId());
        if (sub == null) {
            throw new IllegalArgumentException("关联订阅不存在，无法重试");
        }
        if (!SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            throw new IllegalArgumentException("订阅未在订阅中(当前状态 " + sub.getStatus() + ")，无法重试");
        }
        resetBlockedEpisodes(sub.getId(), record.getEpisode(), record.getEpisodeEnd());
        String keyword = buildKeyword(sub, record.getEpisode());
        return searchSupplementService.supplement(sub.getId(), record.getEpisode(), keyword);
    }

    /**
     * 批量重试失败下载记录：逐条复用单条 retry，用 try/catch 隔离预期内的"跳过"（记录已被并发处理成
     * 非 FAILED、关联订阅已暂停等），不让一条不满足条件的记录中断整批。
     */
    public BatchRetryResult retryBatch(List<Integer> ids) {
        int pushed = 0;
        int skipped = 0;
        for (Integer id : ids) {
            try {
                SupplementResult r = retry(id);
                if (r.isPushed()) {
                    pushed++;
                } else {
                    skipped++;
                }
            } catch (IllegalArgumentException e) {
                skipped++;
            } catch (RuntimeException e) {
                // 批量重试跑在后台线程里，一条意外失败（下载器/索引器抽风）不该让后面的全部作废
                log.warn("批量重试下载记录[#{}]失败，已跳过：{}", id, e.getMessage(), e);
                skipped++;
            }
        }
        log.info("批量重试下载记录完成：共 {} 条，重新推送 {} 条，跳过 {} 条", ids.size(), pushed, skipped);
        return new BatchRetryResult(ids.size(), pushed, skipped);
    }

    /**
     * 忽略 / 取消忽略失败记录。只改 FAILED 的行（条件写在 WHERE 里，选中了别的状态的记录直接不计），
     * 返回实际改到的条数。
     * <p>
     * 忽略只影响「待处理」口径（首页待办、下载记录页的待处理筛选），<b>不碰订阅的集状态</b>：
     * 那一集照旧是缺失，自动补搜仍会继续找。统计仪表盘的失败数也照算——忽略是「不处理」，不是「没失败」。
     * </p>
     */
    public int setFailIgnored(List<Integer> ids, boolean ignored) {
        if (ids.isEmpty()) {
            return 0;
        }
        int changed = recordService.getBaseMapper().update(null, new UpdateWrapper<PtDownloadRecordPlus>()
                .in("id", ids)
                .eq("state", STATE_FAILED)
                .set("fail_ignored", ignored ? FAIL_IGNORED : FAIL_NOT_IGNORED));
        log.info("{}失败下载记录：选中 {} 条，实际改动 {} 条", ignored ? "忽略" : "取消忽略", ids.size(), changed);
        return changed;
    }

    /**
     * 把该订阅下处于 BLOCKED 的目标集重置回 MISSING、失败计数清零。
     * 季包重试（episode 为哨兵值）清空该订阅下所有 BLOCKED 集；区间匹配（episodeEnd 非空）
     * 清空区间内所有 BLOCKED 集，避免只重置起始集导致区间内其余集永远卡在 BLOCKED；
     * 普通单集只清对应那一条。
     */
    private void resetBlockedEpisodes(Integer subId, int episode, Integer episodeEnd) {
        // 一条条件更新搞定：原先先 list 出 BLOCKED 集再逐条 update，季包重试时有几集就打几次库。
        // state=BLOCKED 写在 WHERE 里，本身就是并发保护——被别的路径改走的集不会被拖回 MISSING
        UpdateWrapper<PtSubscriptionEpisodePlus> where = new UpdateWrapper<PtSubscriptionEpisodePlus>()
                .eq("sub_id", subId)
                .eq("state", EP_STATE_BLOCKED);
        if (episode != SubscriptionMatcher.SEASON_PACK) {
            if (episodeEnd != null && episodeEnd > episode) {
                where.ge("episode", episode).le("episode", episodeEnd);
            } else {
                where.eq("episode", episode);
            }
        }
        PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
        set.setState(EP_STATE_MISSING);
        set.setFailCount(0);
        episodeService.update(set, where);
    }

    private String buildKeyword(PtSubscriptionPlus sub, int episode) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return sub.getTitle();
        }
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return sub.getTitle() + " " + seasonCode(sub.getSeason());
        }
        return sub.getTitle() + " " + episodeCode(sub.getSeason(), episode);
    }

    // 展示标签与重试关键词共用这一份编号格式：两边各拼一遍的话，改了一边
    // （比如三位数集号）另一边还是旧格式，页面上写的和实际去搜的就对不上了

    /** {@code S01}；季号为空（订阅已删除）时按 0 处理 */
    private static String seasonCode(Integer season) {
        return "S" + pad(season);
    }

    /** {@code S01E05} */
    private static String episodeCode(Integer season, int episode) {
        return seasonCode(season) + "E" + pad(episode);
    }

    private static String pad(Integer number) {
        int n = number == null ? 0 : number;
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    // ---------- 清理旧记录 ----------

    /** 预览：按 {@link #cleanupConditions} 算出会被清理的条数，不删 */
    public long countCleanable(int days) {
        return recordService.count(cleanupConditions(days));
    }

    /**
     * 清理 N 天前推送、已落定且不再被任何环节引用的下载记录。只删满足<b>全部</b>条件的行：
     * <ol>
     *   <li>终态（COMPLETED / FAILED）——推送中、下载中的还在被追踪；</li>
     *   <li>推送时间早于 N 天前；</li>
     *   <li>不在 H&amp;R 考核中——{@code TorrentCleanService} 与转移做种靠这些行保护正在考核的种子，
     *       删了等于撤掉保护，下一轮删种就可能把它删掉；</li>
     *   <li>没有任何集的 {@code download_id} 指着它——对账写质量基线（洗版的依据）、
     *       企微查询、在途集的回退都要顺着这个 id 找回种子标题，删了洗版就失去基线。</li>
     * </ol>
     * 被删的行同时从统计面板的历史数据里消失，这一点由前端的确认框告诉用户。
     */
    public int cleanup(int days) {
        long before = countCleanable(days);
        if (before == 0) {
            return 0;
        }
        recordService.remove(cleanupConditions(days));
        log.info("已清理 {} 天前的下载记录 {} 条", days, before);
        return (int) before;
    }

    private QueryWrapper<PtDownloadRecordPlus> cleanupConditions(int days) {
        if (!CLEANUP_ALLOWED_DAYS.contains(days)) {
            throw new IllegalArgumentException("保留天数只能是 " + CLEANUP_ALLOWED_DAYS + " 之一");
        }
        Date cutoff = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
        return new QueryWrapper<PtDownloadRecordPlus>()
                .in("state", DownloadRecordState.COMPLETED.value(), STATE_FAILED)
                .lt("pushed_time", cutoff)
                .and(w -> w.isNull("hr_state").or().ne("hr_state", HitAndRunState.PENDING.value()))
                .notExists("SELECT 1 FROM pt_subscription_episode e WHERE e.download_id = pt_download_record.id");
    }
}
