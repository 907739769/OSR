package com.osr.openliststrm.pt.clean;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtCleanRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.service.IPtCleanRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.model.ProtectedTorrents;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.task.HitAndRunState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按规则自动删种，用于「只做种」的下载器（接收 IYUU 转移/辅种过来的种子）腾空间。
 * <p>
 * <b>这是「OSR 从不删种」的第二个受控例外</b>（第一个是
 * {@code DownloadTrackService#removeUselessTorrent}）。删种是不可逆的，且删错的代价不是
 * "少了个文件"而是"站点记一次 H&R"，所以这里的每一条护栏都不是可选项：
 * </p>
 * <ol>
 *   <li><b>总开关默认关闭</b>，且必须逐个下载器显式开启——不存在"升级后自动开始删东西"。</li>
 *   <li><b>没有启用规则就一个都不删</b>。空规则集的语义是"没有任何规则说该删它"，
 *       不是"删掉所有种子"。</li>
 *   <li><b>辅种整组同删</b>：按内容路径分组，组内每个种子都达标才整组删除。
 *       删掉一个种子的文件会让共用这份文件的其余种子立刻变成"文件丢失"，
 *       那是在<b>其它站</b>上记 H&R，而 OSR 连它们属于哪个站都不知道。</li>
 *   <li><b>H&R 未达标不删</b>：OSR 自己的下载记录里 {@code hr_state=PENDING} 的种子受保护，
 *       无论它现在在哪个下载器上（IYUU 转移过去的种子按 hash/标签/种子名都能认回来）。</li>
 *   <li><b>每轮有上限</b>：规则配错时最多损失一轮的量，不会一次清空整个保种盘。</li>
 * </ol>
 * <p>
 * 判定与执行刻意分成两步（{@link #evaluate} / {@link #clean}），预览接口与真正的清理走
 * <b>同一份判定</b>——分叉一次就会出现"预览里说不删的、实际删了"，那比不给预览更糟。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class TorrentCleanService {

    /**
     * qBittorrent 里表示"此刻不该动它"的状态。校验中/移动中删种会留下半截文件；
     * 错误态与文件丢失态则说明现场已经不对了，交给用户判断比自动清理稳妥。
     */
    private static final Set<String> QB_BUSY_STATES = Set.of(
            "checkingup", "checkingdl", "checkingresumedata", "moving", "error", "missingfiles", "allocating");

    /**
     * Transmission 的 status 数值：2=校验中，1=等待校验。其余状态（0 停止、4 下载中、6 做种）
     * 都可以安全删除。Transmission 没有独立的"错误态"数值，错误信息在 errorString 里，
     * 这里不取该字段——保守起见宁可漏拦，qB 才是本功能的主要场景。
     */
    private static final Set<String> TR_BUSY_STATES = Set.of("1", "2");

    private final IPtDownloaderPlusService downloaderService;
    private final IPtCleanRulePlusService ruleService;
    private final IPtDownloadRecordPlusService recordService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final DownloaderClientFactory clientFactory;

    public TorrentCleanService(IPtDownloaderPlusService downloaderService,
                               IPtCleanRulePlusService ruleService,
                               IPtDownloadRecordPlusService recordService,
                               IPtSubscriptionEpisodePlusService episodeService,
                               DownloaderClientFactory clientFactory) {
        this.downloaderService = downloaderService;
        this.ruleService = ruleService;
        this.recordService = recordService;
        this.episodeService = episodeService;
        this.clientFactory = clientFactory;
    }

    /**
     * 扫描全部启用且开了自动删种的下载器并执行清理。定时任务的入口。
     * <p>
     * 单个下载器失败只记 warn 并继续下一个：一台保种机连不上，不该让另一台的清理也停摆。
     * </p>
     */
    public List<CleanSummary> cleanAll() {
        List<PtDownloaderPlus> downloaders = downloaderService.list(
                new QueryWrapper<PtDownloaderPlus>().eq("enabled", "1"));
        List<CleanSummary> summaries = new ArrayList<>();
        for (PtDownloaderPlus downloader : downloaders) {
            if (!downloader.autoDeleteOn()) {
                continue;
            }
            try {
                summaries.add(clean(downloader));
            } catch (Exception e) {
                log.warn("下载器[{}] 自动删种失败：{}", downloader.getName(), e.getMessage());
            }
        }
        return summaries;
    }

    /**
     * 对单个下载器执行清理。手动触发也走这里。
     *
     * @throws Exception 下载器不可达等，交由调用方决定是否继续
     */
    public CleanSummary clean(PtDownloaderPlus downloader) throws Exception {
        List<CleanGroupDecision> decisions = evaluate(downloader);
        CleanSummary summary = new CleanSummary();
        summary.setDownloaderName(downloader.getName());
        summary.setScannedGroups(decisions.size());
        if (decisions.isEmpty() && ruleService.listEnabledByDownloader(downloader.getId()).isEmpty()) {
            summary.setNoRules(true);
            return summary;
        }

        IDownloaderClient client = clientFactory.get(downloader);
        for (CleanGroupDecision decision : decisions) {
            if (!decision.isDeletable()) {
                continue;
            }
            if (deleteGroup(client, downloader, decision)) {
                summary.addDeleted(decision);
            } else {
                summary.setFailedGroups(summary.getFailedGroups() + 1);
            }
        }
        notifySummary(summary);
        return summary;
    }

    /**
     * 只判定不执行：拉取种子、分组、逐组给出可删/不可删与原因。预览接口直接用它。
     * <p>
     * 返回的顺序是<b>按组体积从大到小</b>：每轮删除有上限，先删大的才能用最少的删除次数
     * 腾出最多空间；预览列表里用户最关心的也是"最占地方的那些能不能删"。
     * </p>
     *
     * @throws Exception 下载器不可达
     */
    public List<CleanGroupDecision> evaluate(PtDownloaderPlus downloader) throws Exception {
        List<PtCleanRulePlus> rules = ruleService.listEnabledByDownloader(downloader.getId());
        if (rules.isEmpty()) {
            log.info("下载器[{}] 开了自动删种但没有任何启用规则，本轮不做任何删除", downloader.getName());
            return List.of();
        }
        List<DownloaderTorrent> torrents = clientFactory.get(downloader).listAll(downloader);
        Set<String> excludeTags = parseTags(downloader.getAutoDeleteExcludeTags());
        ProtectedTorrents hrProtected = ProtectedTorrents.of(loadHitAndRunPending());
        ProtectedTorrents uploadProtected = ProtectedTorrents.of(loadRecordsWithUnsettledEpisodes());

        List<CleanGroupDecision> decisions = new ArrayList<>();
        for (Map.Entry<String, List<DownloaderTorrent>> group : groupByContent(torrents).entrySet()) {
            decisions.add(judgeGroup(group.getKey(), group.getValue(), rules, excludeTags,
                    hrProtected, uploadProtected));
        }
        decisions.sort(Comparator.comparingLong(CleanGroupDecision::sizeBytes).reversed());
        return applyRoundLimit(decisions, downloader.getAutoDeleteMaxPerRound());
    }

    /**
     * 把超出本轮上限的可删组改判成"留到下一轮"。
     * <p>
     * 上限作用在<b>可删组</b>上而不是全部组上：一台保种机可能有几百个组，其中只有三五个达标，
     * 按全部组数截断会让达标的组因为排在后面而永远轮不上。
     * </p>
     */
    private List<CleanGroupDecision> applyRoundLimit(List<CleanGroupDecision> decisions, Integer maxPerRound) {
        if (maxPerRound == null || maxPerRound <= 0) {
            return decisions;
        }
        int remaining = maxPerRound;
        List<CleanGroupDecision> limited = new ArrayList<>(decisions.size());
        int dropped = 0;
        for (CleanGroupDecision decision : decisions) {
            if (!decision.isDeletable()) {
                limited.add(decision);
                continue;
            }
            if (remaining > 0) {
                remaining--;
                limited.add(decision);
            } else {
                dropped++;
                limited.add(CleanGroupDecision.skip(decision.getContentKey(), decision.getTorrents(),
                        CleanSkipReason.ROUND_LIMIT, null));
            }
        }
        if (dropped > 0) {
            // 静默截断会被读成"已经清干净了"，必须说出来
            log.info("本轮删除上限 {} 已用尽，还有 {} 个达标的辅种组留到下一轮", maxPerRound, dropped);
        }
        return limited;
    }

    /**
     * 判定一个辅种组能不能删。
     * <p>
     * 逐个种子检查，<b>任一个不达标整组保留</b>。检查顺序按"越是硬护栏越靠前"排列，
     * 这样返回的原因是最有解释力的那一条（H&R 未达标比"做种时长不够"更值得告诉用户）。
     * </p>
     */
    private CleanGroupDecision judgeGroup(String contentKey, List<DownloaderTorrent> group,
                                          List<PtCleanRulePlus> rules, Set<String> excludeTags,
                                          ProtectedTorrents hrProtected, ProtectedTorrents uploadProtected) {
        boolean deleteFiles = false;
        for (DownloaderTorrent torrent : group) {
            if (!torrent.isCompleted()) {
                return CleanGroupDecision.skip(contentKey, group, CleanSkipReason.NOT_COMPLETED, torrent.getName());
            }
            if (isBusy(torrent)) {
                return CleanGroupDecision.skip(contentKey, group, CleanSkipReason.BUSY_STATE, torrent.getName());
            }
            if (hasExcludedTag(torrent, excludeTags)) {
                return CleanGroupDecision.skip(contentKey, group, CleanSkipReason.EXCLUDED_TAG, torrent.getName());
            }
            if (hrProtected.covers(torrent)) {
                return CleanGroupDecision.skip(contentKey, group, CleanSkipReason.HIT_AND_RUN_PENDING,
                        torrent.getName());
            }
            if (uploadProtected.covers(torrent)) {
                return CleanGroupDecision.skip(contentKey, group, CleanSkipReason.UPLOAD_PENDING,
                        torrent.getName());
            }
            PtCleanRulePlus rule = matchRule(rules, torrent.getSize());
            if (rule == null) {
                return CleanGroupDecision.skip(contentKey, group, CleanSkipReason.NO_RULE_MATCHED, torrent.getName());
            }
            if (torrent.getSeedingSeconds() < rule.minSeedSeconds()) {
                return CleanGroupDecision.skip(contentKey, group, CleanSkipReason.SEED_TIME_NOT_REACHED,
                        torrent.getName());
            }
            // 组内规则可能不一致（不同体积口径命中不同规则）。只要有一条规则要求删文件就删——
            // 留着文件而把种子全删了，等于既腾不出空间又失去了重新做种的可能，是最差的组合
            deleteFiles = deleteFiles || rule.deleteFilesToo();
        }
        return CleanGroupDecision.deletable(contentKey, group, deleteFiles);
    }

    /**
     * 取第一条体积区间命中的规则；一条都命中不了返回 null（= 不删）。
     * 规则已按 sort_order 升序，顺序即优先级。
     */
    private PtCleanRulePlus matchRule(List<PtCleanRulePlus> rules, long sizeBytes) {
        for (PtCleanRulePlus rule : rules) {
            if (rule.sizeMatches(sizeBytes)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 执行一个组的删除。
     * <p>
     * <b>顺序是硬要求：先删不带文件的兄弟，最后一个才连文件一起删。</b>反过来的话，
     * 文件在第一步就没了，剩下的兄弟种子会先变成"文件丢失"状态——即便随后也被删掉，
     * 中间这段窗口足够下载器向站点汇报一次异常。而且一旦中途某一步失败，
     * 现在这个顺序保证文件还在，整组可以在下一轮原样重来。
     * </p>
     *
     * @return 整组是否删干净了
     */
    private boolean deleteGroup(IDownloaderClient client, PtDownloaderPlus downloader,
                                CleanGroupDecision decision) {
        List<DownloaderTorrent> group = decision.getTorrents();
        for (int i = 0; i < group.size(); i++) {
            DownloaderTorrent torrent = group.get(i);
            boolean last = i == group.size() - 1;
            boolean withFiles = last && decision.isDeleteFiles();
            try {
                client.deleteTorrent(downloader, torrent.getHash(), withFiles);
            } catch (Exception e) {
                log.warn("下载器[{}] 删除种子[{}]失败，本组剩余种子保持原样（文件未删，下一轮重试）：{}",
                        downloader.getName(), torrent.getName(), e.getMessage());
                return false;
            }
        }
        log.info("下载器[{}] 已清理辅种组「{}」：{} 个种子，释放 {}", downloader.getName(),
                decision.displayName(), group.size(), formatSize(decision.sizeBytes()));
        return true;
    }

    /**
     * 按内容路径分组。用 {@link LinkedHashMap} 保持下载器返回的顺序，
     * 让同一批种子在多次运行间给出稳定的处理顺序，排查时可复现。
     */
    private Map<String, List<DownloaderTorrent>> groupByContent(List<DownloaderTorrent> torrents) {
        Map<String, List<DownloaderTorrent>> groups = new LinkedHashMap<>();
        for (DownloaderTorrent torrent : torrents) {
            String key = torrent.contentKey();
            if (StringUtils.isBlank(key)) {
                // 连 hash 都没有的种子无法定位，跳过比瞎归组安全
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(torrent);
        }
        return groups;
    }

    private boolean isBusy(DownloaderTorrent torrent) {
        String state = torrent.getRawState();
        if (StringUtils.isBlank(state)) {
            // 状态读不出来时按"忙"处理：判据缺失时不动手，是本功能的一贯取向
            return true;
        }
        String normalized = state.toLowerCase(Locale.ROOT);
        return QB_BUSY_STATES.contains(normalized) || TR_BUSY_STATES.contains(normalized);
    }

    private boolean hasExcludedTag(DownloaderTorrent torrent, Set<String> excludeTags) {
        if (excludeTags.isEmpty() || StringUtils.isBlank(torrent.getTags())) {
            return false;
        }
        for (String tag : torrent.getTags().split(",")) {
            if (excludeTags.contains(tag.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> parseTags(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Set.of();
        }
        Set<String> tags = new HashSet<>();
        for (String tag : raw.split(",")) {
            String trimmed = tag.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }

    /**
     * 仍在 H&R 考核中的下载记录：删了就是记一次过。
     * <p>
     * <b>不限下载器</b>：种子可能已经被 IYUU 转移到别的下载器上了，而记录里的 downloader_id
     * 未必已经跟上（{@code DownloadTrackService} 发现转移后才会改）。少查一个条件的代价
     * 只是名单大了一点，漏掉一条的代价不可逆。
     * </p>
     */
    private List<PtDownloadRecordPlus> loadHitAndRunPending() {
        return recordService.list(new QueryWrapper<PtDownloadRecordPlus>()
                .eq("hr_state", HitAndRunState.PENDING.value()));
    }

    /**
     * 还有集停在 IN_FLIGHT/UPGRADING 的下载记录。
     * <p>
     * 种子下完了不代表活干完了——文件还要被同步链路上传到网盘，网盘秒传不命中时只能真传，
     * 大文件跨天、中途失败重来都是常态（这一点在 {@code file_confirmed} 的设计里已经写明）。
     * 这段时间里把保种文件删掉，等于亲手制造一个"下载记录显示成功、媒体库里永远不出现"的集，
     * 而且重下一遍还要多背一份 H&R 义务。
     * </p>
     * <p>
     * 与 H&R 名单分开而不是合成一份，是为了让预览里的跳过原因说得准：用户看到
     * "H&R 考核未达标"会去站点查考核进度，而实际该看的是复制记录页的上传进度。
     * </p>
     */
    private List<PtDownloadRecordPlus> loadRecordsWithUnsettledEpisodes() {
        List<PtSubscriptionEpisodePlus> unsettled = episodeService.list(
                new QueryWrapper<PtSubscriptionEpisodePlus>()
                        .isNotNull("download_id")
                        .in("state", SubscriptionEpisodeState.IN_FLIGHT.value(),
                                SubscriptionEpisodeState.UPGRADING.value()));
        List<Integer> recordIds = unsettled.stream()
                .map(PtSubscriptionEpisodePlus::getDownloadId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (recordIds.isEmpty()) {
            return List.of();
        }
        return recordService.listByIds(recordIds);
    }

    private void notifySummary(CleanSummary summary) {
        if (summary.getDeletedGroups() == 0 && summary.getFailedGroups() == 0) {
            // 什么都没删是常态（多数轮次如此），静默即可
            return;
        }
        StringBuilder msg = new StringBuilder("🧹 自动删种：下载器 ")
                .append(StringUtils.escapeHtml(summary.getDownloaderName()))
                .append("\n已清理 ").append(summary.getDeletedGroups()).append(" 组（")
                .append(summary.getDeletedTorrents()).append(" 个种子），释放 ")
                .append(summary.freedText());
        if (summary.getFailedGroups() > 0) {
            msg.append("\n⚠️ ").append(summary.getFailedGroups())
                    .append(" 组删除失败，文件未删除，将在下一轮重试");
        }
        try {
            TgHelper.sendMsg(NotificationType.GENERAL, msg.toString());
        } catch (Exception e) {
            log.debug("自动删种汇总通知发送失败（不影响清理结果）：{}", e.getMessage());
        }
    }

    private String formatSize(long bytes) {
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
