package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import com.osr.openliststrm.pt.upgrade.UpgradeConfigAdminService;
import com.osr.openliststrm.pt.upgrade.UpgradeConfigCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 过滤规则页背后的服务：保存（含校验，以及对洗版规则的连带影响）与规则试算。
 *
 * @author Jack
 */
@Slf4j
@Service
public class FilterConfigAdminService {

    private final IPtFilterConfigPlusService filterConfigService;
    private final IPtUpgradeConfigPlusService upgradeConfigService;
    private final IPtTorrentBlacklistPlusService blacklistService;
    private final UpgradeConfigAdminService upgradeAdminService;
    private final SubscriptionEngine subscriptionEngine;
    private final TorrentFilterEngine filterEngine;

    public FilterConfigAdminService(IPtFilterConfigPlusService filterConfigService,
                                    IPtUpgradeConfigPlusService upgradeConfigService,
                                    IPtTorrentBlacklistPlusService blacklistService,
                                    UpgradeConfigAdminService upgradeAdminService,
                                    SubscriptionEngine subscriptionEngine,
                                    TorrentFilterEngine filterEngine) {
        this.filterConfigService = filterConfigService;
        this.upgradeConfigService = upgradeConfigService;
        this.blacklistService = blacklistService;
        this.upgradeAdminService = upgradeAdminService;
        this.subscriptionEngine = subscriptionEngine;
        this.filterEngine = filterEngine;
    }

    /**
     * 保存全局过滤规则。
     * <p>
     * 洗版沿用这里的分辨率/来源/发布组优先级。洗版开着时，<b>这次修改新引入</b>的不一致会被拒绝
     * （比如把来源优先级清空，而洗版目标来源还是 REMUX）——在这一页改完保存成功，
     * 洗版却就此静默失效，是两页分开配置最容易踩的坑。原本就存在的不一致不拦，免得这一页
     * 因为另一页的问题存不了。优先级变了会重置洗版的评估结论，见
     * {@link UpgradeConfigAdminService#resetEvaluations()}。
     * </p>
     *
     * @return 拒绝保存的原因，空表示已保存
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> save(PtFilterConfigPlus config) {
        config.setId(PtFilterConfigPlus.SINGLETON_ID);
        List<String> errors = new ArrayList<>(FilterConfigCheck.errors(config));
        PtFilterConfigPlus old = filterConfigService.getConfig();
        PtUpgradeConfigPlus upgrade = upgradeConfigService.getConfig();
        if ("1".equals(upgrade.getEnabled())) {
            List<String> before = UpgradeConfigCheck.problems(upgrade, old);
            for (String problem : UpgradeConfigCheck.problems(upgrade, config)) {
                if (!before.contains(problem)) {
                    errors.add("洗版规则依赖这里的优先级，这次修改会让它失效：" + problem);
                }
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        if (!filterConfigService.saveOrUpdate(config)) {
            return List.of("保存失败");
        }
        if (UpgradeConfigAdminService.prioritiesChanged(old, config)) {
            int reset = upgradeAdminService.resetEvaluations();
            log.info("过滤规则的优先级已修改，重置 {} 集的洗版评估结论", reset);
        }
        return List.of();
    }

    /**
     * 规则试算：拿一条种子标题（可带描述、体积、做种数等）过一遍给定的过滤规则，
     * 返回解析结果与判定。规则取调用方传入的<b>未保存</b>版本，用户改完能先试再存。
     * 黑名单取已保存的——它不在这一页配置。
     */
    public PreviewResult preview(PreviewRequest request) {
        TorrentInfo torrent = new TorrentInfo();
        torrent.setTitle(request.title() == null ? "" : request.title().trim());
        torrent.setDescription(request.description());
        torrent.setSize(request.size() == null ? 0 : request.size());
        torrent.setSeeders(request.seeders() == null ? 0 : request.seeders());
        torrent.setDownloadVolumeFactor(Boolean.TRUE.equals(request.free()) ? 0 : 1.0);
        torrent.setHitAndRun(Boolean.TRUE.equals(request.hitAndRun()));
        torrent.setGuid("preview");
        subscriptionEngine.fillParsed(torrent);
        EpisodeCountResolver.apply(List.of(torrent), null, false);

        PtFilterConfigPlus config = request.config() == null ? filterConfigService.getConfig() : request.config();
        FilterCriteria criteria = FilterCriteriaFactory.build(config, null);
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());
        // 只有「按外语电影判定」时才带原始语言，与订阅链路一致：中字检查只对外语电影生效
        String language = Boolean.TRUE.equals(request.foreignMovie()) ? "en" : null;
        TorrentFilterEngine.Verdict verdict =
                filterEngine.evaluate(List.of(torrent), criteria, blacklist, language).get(0);

        return new PreviewResult(
                torrent.getParsedTitle(), torrent.getParsedYear(), torrent.getParsedSeason(),
                torrent.getParsedEpisode(), torrent.getParsedEpisodeEnd(), torrent.getEpisodeCount(),
                torrent.getParsedResolution(), torrent.getParsedSource(), torrent.getParsedReleaseGroup(),
                torrent.getParsedTags(), criteria.effectiveSize(torrent),
                verdict.accepted(),
                verdict.rejectCode() == null ? null : verdict.rejectCode().value(),
                verdict.rejectCode() == null ? null : verdict.rejectCode().label(),
                verdict.rejectReason());
    }

    /**
     * @param size         体积（字节）
     * @param free         是否免费种
     * @param hitAndRun    来源站点是否有 H&R
     * @param foreignMovie 是否按外语电影判定（影响「外语电影需中字」）
     * @param config       待试算的过滤规则；null 表示用已保存的
     */
    public record PreviewRequest(String title, String description, Long size, Integer seeders, Boolean free,
                                 Boolean hitAndRun, Boolean foreignMovie, PtFilterConfigPlus config) {
    }

    /**
     * @param effectiveSize 参与体积判定的体积（按每集判定时已折算）
     */
    public record PreviewResult(String title, String year, Integer season, Integer episode, Integer episodeEnd,
                                int episodeCount, String resolution, String source, String releaseGroup,
                                List<String> tags, long effectiveSize, boolean accepted, String rejectCode,
                                String rejectLabel, String rejectReason) {
    }
}
