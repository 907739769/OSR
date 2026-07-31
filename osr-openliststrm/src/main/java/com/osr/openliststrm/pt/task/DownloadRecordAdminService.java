package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.core.domain.PageResult;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.subscription.SubscriptionMatcher;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.dto.SupplementResult;
import com.osr.openliststrm.pt.task.dto.BatchRetryResult;
import com.osr.openliststrm.pt.task.dto.DownloadRecordView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 下载记录管理台：列表展示的名称拼接 + 失败记录的手动重试。
 * 与 {@link DownloadTrackService} 分开——那个是自动轮询编排，这个是面向管理界面的读操作与人工干预。
 *
 * @author Jack
 */
@Service
public class DownloadRecordAdminService {

    private static final String STATE_FAILED = DownloadRecordState.FAILED.value();
    private static final String EP_STATE_BLOCKED = SubscriptionEpisodeState.BLOCKED.value();
    private static final String EP_STATE_MISSING = SubscriptionEpisodeState.MISSING.value();

    private final IPtDownloadRecordPlusService recordService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtIndexerPlusService indexerService;
    private final IPtDownloaderPlusService downloaderService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final SearchSupplementService searchSupplementService;

    public DownloadRecordAdminService(IPtDownloadRecordPlusService recordService,
                                      IPtSubscriptionPlusService subscriptionService,
                                      IPtIndexerPlusService indexerService,
                                      IPtDownloaderPlusService downloaderService,
                                      IPtSubscriptionEpisodePlusService episodeService,
                                      SearchSupplementService searchSupplementService) {
        this.recordService = recordService;
        this.subscriptionService = subscriptionService;
        this.indexerService = indexerService;
        this.downloaderService = downloaderService;
        this.episodeService = episodeService;
        this.searchSupplementService = searchSupplementService;
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
        return PageResult.of(views, page.getTotal(), page.getPage(), page.getSize());
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
        view.setSize(r.getSize());
        view.setSeeders(r.getSeeders());
        view.setState(r.getState());
        view.setProgress(r.getProgress());
        view.setFailReason(r.getFailReason());
        view.setFailReasonCode(r.getFailReasonCode());
        view.setPushedTime(r.getPushedTime());
        view.setCompletedTime(r.getCompletedTime());
        return view;
    }

    private String episodeLabel(PtSubscriptionPlus sub, int episode, Integer episodeEnd) {
        if (sub != null && SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return "电影";
        }
        Integer season = sub == null ? null : sub.getSeason();
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return "S" + pad(season) + " 季包";
        }
        if (episodeEnd != null && episodeEnd > episode) {
            return "S" + pad(season) + "E" + pad(episode) + "-E" + pad(episodeEnd);
        }
        return "S" + pad(season) + "E" + pad(episode);
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
            }
        }
        return new BatchRetryResult(ids.size(), pushed, skipped);
    }

    /**
     * 把该订阅下处于 BLOCKED 的目标集重置回 MISSING、失败计数清零。
     * 季包重试（episode 为哨兵值）清空该订阅下所有 BLOCKED 集；区间匹配（episodeEnd 非空）
     * 清空区间内所有 BLOCKED 集，避免只重置起始集导致区间内其余集永远卡在 BLOCKED；
     * 普通单集只清对应那一条。
     */
    private void resetBlockedEpisodes(Integer subId, int episode, Integer episodeEnd) {
        QueryWrapper<PtSubscriptionEpisodePlus> query = new QueryWrapper<PtSubscriptionEpisodePlus>()
                .eq("sub_id", subId)
                .eq("state", EP_STATE_BLOCKED);
        if (episode != SubscriptionMatcher.SEASON_PACK) {
            if (episodeEnd != null && episodeEnd > episode) {
                query.ge("episode", episode).le("episode", episodeEnd);
            } else {
                query.eq("episode", episode);
            }
        }
        List<PtSubscriptionEpisodePlus> blocked = episodeService.list(query);
        for (PtSubscriptionEpisodePlus ep : blocked) {
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(EP_STATE_MISSING);
            set.setFailCount(0);
            episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", ep.getId())
                    .eq("state", EP_STATE_BLOCKED));
        }
    }

    private String buildKeyword(PtSubscriptionPlus sub, int episode) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return sub.getTitle();
        }
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return sub.getTitle() + " S" + pad(sub.getSeason());
        }
        return sub.getTitle() + " S" + pad(sub.getSeason()) + "E" + pad(episode);
    }

    private String pad(Integer number) {
        int n = number == null ? 0 : number;
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
