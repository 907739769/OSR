package com.osr.openliststrm.pt.search;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.filter.EpisodeCountResolver;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.FilterCriteriaFactory;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import com.osr.openliststrm.pt.subscription.dto.SearchCandidateDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源搜索页：不建订阅，直接拿关键词搜全部站点，看站上有什么，再决定直接下载还是转为订阅。
 * <p>
 * 与订阅内手动搜索的区别有两条，都是刻意的：
 * </p>
 * <ul>
 *   <li><b>不做「是不是这部作品」的过滤</b>——没有订阅可比。检索、去重、标题解析、H&R 标记与订阅内
 *       手动搜索共用同一份实现（{@link SearchSupplementService#searchKeyword}），两边看到的种子画像一致。</li>
 *   <li><b>全局过滤规则只标注、不淘汰</b>（{@link SearchCandidateDTO#getRuleRejection}）。用户来这里正是要看
 *       「站上到底有什么」，按规则滤掉一批的话他会以为站上没有；而标注出「这条会因为分辨率不在白名单被挡」，
 *       恰好回答了「为什么我订了却没自动下」。用的是全局规则、不带任何订阅级覆盖。</li>
 * </ul>
 * <p>
 * <b>直接下载不建下载记录</b>：{@code pt_download_record.sub_id} 是 NOT NULL，下载追踪、记录页的归属隔离、
 * 统计都以「记录属于某个订阅」为前提，为这一个入口放开要动三十来处。因此直接下载的种子在 OSR 看来
 * 与用户在下载器里手动添加的种子是一回事：不追踪进度、不下发 H&R 分享限制、不出现在下载记录页；
 * 下载完成后的同步/STRM 链路是按目录触发的，照常生效。想要被追踪就走「转为订阅」。
 * </p>
 */
@Slf4j
@Service
public class ResourceSearchService {

    /** 直接下载的种子打这个标签：与订阅推送的跟踪标签分开，不会被下载追踪认领 */
    public static final String MANUAL_TAG = "osr-manual";

    /** 关键词至少这么长：一个字的关键词在多数站点上要么报错、要么返回整站最新，都不是用户要的 */
    static final int MIN_KEYWORD_LENGTH = 2;

    /**
     * 搜索结果。
     *
     * @param candidateCount 去重后的候选数
     * @param rejectedCount  其中会被全局过滤规则淘汰的条数
     */
    public record Result(int candidateCount, int rejectedCount, List<SearchCandidateDTO> items) {
    }

    private final SearchSupplementService searchSupplementService;
    private final SubscriptionEngine subscriptionEngine;
    private final TorrentFilterEngine filterEngine;
    private final IPtFilterConfigPlusService filterConfigService;
    private final IPtTorrentBlacklistPlusService blacklistService;
    private final IPtDownloaderPlusService downloaderService;
    private final DownloaderClientFactory downloaderClientFactory;

    public ResourceSearchService(SearchSupplementService searchSupplementService,
                                 SubscriptionEngine subscriptionEngine,
                                 TorrentFilterEngine filterEngine,
                                 IPtFilterConfigPlusService filterConfigService,
                                 IPtTorrentBlacklistPlusService blacklistService,
                                 IPtDownloaderPlusService downloaderService,
                                 DownloaderClientFactory downloaderClientFactory) {
        this.searchSupplementService = searchSupplementService;
        this.subscriptionEngine = subscriptionEngine;
        this.filterEngine = filterEngine;
        this.filterConfigService = filterConfigService;
        this.blacklistService = blacklistService;
        this.downloaderService = downloaderService;
        this.downloaderClientFactory = downloaderClientFactory;
    }

    /**
     * @throws IllegalArgumentException 关键词为空或太短、没有启用中的索引器、所选站点全部不可用
     */
    public Result search(String keyword, Collection<Integer> indexerIds) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() < MIN_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("关键词至少 " + MIN_KEYWORD_LENGTH + " 个字");
        }
        // 没有索引器时直说，别让用户拿着「0 个结果」去换关键词（同订阅内手动搜索）
        if (searchSupplementService.hasNoEnabledIndexer()) {
            throw new IllegalArgumentException("没有启用中的索引器，无法搜索。请到「PT索引器」页面添加或启用至少一个索引器");
        }
        List<TorrentInfo> torrents = new ArrayList<>(searchSupplementService.searchKeyword(kw, indexerIds));
        // 与订阅内手动搜索同样先打 H&R 标记、折算集数：前者让「规避 H&R」的标注生效，后者让按每集判定的
        // 体积规则对季包给出与订阅链路一致的结论。没有订阅总集数可参考，按种子自身的区间/文件数估
        subscriptionEngine.markHitAndRun(torrents);
        for (TorrentInfo t : torrents) {
            t.setEpisodeCount(EpisodeCountResolver.resolve(t, null, looksLikeMovie(t)));
        }
        Map<TorrentInfo, TorrentFilterEngine.Verdict> verdicts = evaluate(torrents);

        // 默认按做种数降序：页面上能再按列排序，这里只决定第一眼看到什么
        torrents.sort(Comparator.comparingInt(TorrentInfo::getSeeders).reversed());
        List<SearchCandidateDTO> items = searchSupplementService.toCandidateDtos(torrents);
        int rejected = 0;
        for (int i = 0; i < torrents.size(); i++) {
            TorrentFilterEngine.Verdict verdict = verdicts.get(torrents.get(i));
            if (verdict != null && !verdict.accepted()) {
                items.get(i).setRuleRejection(verdict.rejectCode() == null ? "被过滤规则淘汰" : verdict.rejectCode().label());
                items.get(i).setRuleRejectionDetail(verdict.rejectReason());
                rejected++;
            }
        }
        log.info("资源搜索 关键词[{}]：{} 个结果，其中 {} 个会被全局过滤规则淘汰", kw, items.size(), rejected);
        return new Result(items.size(), rejected, items);
    }

    /** 按全局规则逐条判定；判定本身出错不该让搜索失败，退回成「不标注」 */
    private Map<TorrentInfo, TorrentFilterEngine.Verdict> evaluate(List<TorrentInfo> torrents) {
        Map<TorrentInfo, TorrentFilterEngine.Verdict> byTorrent = new IdentityHashMap<>();
        try {
            FilterCriteria criteria = FilterCriteriaFactory.build(filterConfigService.getConfig(), null);
            TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());
            // 原始语言未知（没有订阅就没有 TMDb 条目），传 null 跳过「中字」检查——那一项要按影片原语言判
            for (TorrentFilterEngine.Verdict v : filterEngine.evaluate(torrents, criteria, blacklist, null)) {
                byTorrent.put(v.torrent(), v);
            }
        } catch (Exception e) {
            log.warn("资源搜索按全局过滤规则标注失败，本次结果不带标注：{}", e.getMessage(), e);
        }
        return byTorrent;
    }

    /**
     * 直接推给指定下载器，不建下载记录（理由见类注释）。
     *
     * @return 实际落盘目录，回给用户看
     * @throws IllegalArgumentException 下载器不存在/停用/只做种、缺下载链接、下载器拒绝
     */
    public String push(ResourcePushRequest request) {
        if (request.getDownloaderId() == null) {
            throw new IllegalArgumentException("请选择下载器");
        }
        if (StringUtils.isBlank(request.getDownloadUrl())) {
            throw new IllegalArgumentException("该候选没有下载链接，无法推送");
        }
        PtDownloaderPlus downloader = downloaderService.getById(request.getDownloaderId());
        if (downloader == null || !"1".equals(downloader.getEnabled())) {
            throw new IllegalArgumentException("所选下载器不存在或已停用");
        }
        // 与订阅推送同一条分工：只做种的机器开着按「保种」设计的清理规则，新下载推上去会被当保种种子清掉
        if (!downloader.participatesInDownload()) {
            throw new IllegalArgumentException("下载器「" + downloader.getName() + "」只用于做种，不接受新下载，请换一台");
        }
        TorrentInfo torrent = SearchSupplementService.torrentOf(request);
        subscriptionEngine.fillParsed(torrent);
        String savePath = SubscriptionEngine.savePathFor(downloader, looksLikeMovie(torrent), torrent.getParsedYear());
        try {
            downloaderClientFactory.get(downloader).addTorrent(downloader, torrent.getDownloadUrl(), savePath, MANUAL_TAG, false);
        } catch (Exception e) {
            log.warn("资源搜索直接下载失败：{} → 下载器「{}」：{}", torrent.getTitle(), downloader.getName(), e.getMessage(), e);
            throw new IllegalArgumentException("推送到下载器「" + downloader.getName() + "」失败：" + e.getMessage());
        }
        log.info("资源搜索直接下载：{} → 下载器「{}」，目录 {}（不建下载记录）", torrent.getTitle(), downloader.getName(), savePath);
        return savePath;
    }

    /** 解析不出季号也解析不出集号的，按电影处理（决定智能分类落进「电影」还是「剧集」目录） */
    static boolean looksLikeMovie(TorrentInfo torrent) {
        return torrent.getParsedSeason() == null && torrent.getParsedEpisode() == null;
    }
}
