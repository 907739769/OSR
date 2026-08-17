package com.osr.openliststrm.pt.transfer;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRulePlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRulePlusService;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.AddTorrentOutcome;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import com.osr.openliststrm.pt.model.ProtectedTorrents;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.task.HitAndRunState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 转移做种：把源下载器上已完成的种子搬到目标下载器继续做种，数据文件原地不动。
 * <p>
 * 这是 IYUU「转移」那部分能力的自建实现。<b>不包含「辅种」</b>——辅种要拿 infohash 去
 * 其它站点找同一份资源，依赖 IYUU 服务端的站点索引与各站 passkey，OSR 没有这些数据。
 * </p>
 *
 * <h3>为什么是跨轮次的状态机</h3>
 * <p>
 * 目标下载器接手之前必须先校验一遍本地数据，而校验要跑几分钟到几十分钟。因此一次转移被
 * 拆成两段：本轮「导出 → 暂停态加种 → 触发校验」，下一轮（或再下一轮）「读校验结果 →
 * 启动做种 → 删源端种子」。中间态落在 {@code pt_transfer_record} 上而不是内存里——
 * 放内存的话进程一重启，目标端就留下一批暂停态的孤儿种子，既不做种也没人再管。
 * </p>
 *
 * <h3>三条不能改坏的约束</h3>
 * <ol>
 *   <li><b>目标端一律以暂停态加入、校验通过后才启动。</b>直接以运行态加进去的话，一旦保存
 *       路径对不上（路径映射配错是本功能最常见的故障），下载器会把整个种子<b>重新下载一遍</b>，
 *       几十 GB 的流量和一次可能的文件覆盖就这么出去了。</li>
 *   <li><b>校验不通过时撤销目标端的种子，且 deleteFiles 恒为 false。</b>那份文件是源下载器
 *       正在做种的数据，删掉它等于同时毁掉源端的种子——这是整个功能里唯一能造成真实数据
 *       损失的一步。撤销前还必须确认种子是本次转移<b>新加</b>的（见 {@link AddTorrentOutcome}），
 *       否则会替用户删掉一个他自己加的任务。</li>
 *   <li><b>删源端种子是「OSR 从不删种」的第三个受控例外</b>（前两个是
 *       {@code DownloadTrackService#removeUselessTorrent} 与 {@code TorrentCleanService}）。
 *       边界：只在目标端校验到 100% 且已启动之后、只删种不删文件、可逐规则关掉。</li>
 * </ol>
 *
 * <h3>为什么 H&R 考核中的种子不转移</h3>
 * <p>
 * 换下载器之后做种时长要从零重新累计（那是下载器自己的计时口径），OSR 的考核追踪会因此
 * 推迟达标。更要紧的是站点的 H&R 要求是以<b>种子级限额</b>下发到下载器上的
 * （{@code setShareLimits}），限额不会跟着种子搬家。把 PENDING 的种子挡在门外，顺带
 * 使得"转移过去的种子还需不需要重新下发限额"这个问题不存在——能搬的种子要么已经达标，
 * 要么本来就没有 H&R 要求。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class TorrentTransferService {

    /**
     * 源下载器里表示"此刻不该动它"的状态，取值与自动删种的 {@code QB_BUSY_STATES} 同源。
     * 校验中/移动中导出种子可能拿到半截状态；错误态与文件丢失说明现场已经不对了，
     * 这时候搬家只会把问题带到另一台机器上。
     */
    private static final Set<String> QB_BUSY_STATES = Set.of(
            "checkingup", "checkingdl", "checkingresumedata", "moving", "error", "missingfiles", "allocating");

    /** Transmission 的 status 数值：1=等待校验，2=校验中 */
    private static final Set<String> TR_BUSY_STATES = Set.of("1", "2");

    /** 加种后等待种子在目标端可见的轮询次数与间隔 */
    private static final int VISIBILITY_ATTEMPTS = 10;
    private static final long VISIBILITY_INTERVAL_MS = 1000L;

    /**
     * 同一个种子在同一条规则下允许失败的次数，超过就不再自动重试。
     * <p>
     * 源端种子在一次失败之后状态不会变，条件依旧满足，不拦的话每一轮都会原样再试一次、
     * 再失败一次、再发一条通知——用户看到的是「一直在报同一个错」。留 3 次是为了让
     * 网络抖动、下载器重启这类一次性故障还能自愈；持续性的故障（路径映射配错是最常见的
     * 那个）第 4 轮起就安静下来，改完配置删掉记录即可重来。
     * </p>
     */
    private static final long MAX_FAILURES_PER_TORRENT = 3;

    /** 两次重试之间的最小间隔：失败原因多半要人去改配置，几分钟一轮地重试没有意义 */
    private static final long RETRY_COOLDOWN_MS = 6 * 60 * 60 * 1000L;

    private final IPtTransferRulePlusService ruleService;
    private final IPtTransferRecordPlusService recordService;
    private final IPtDownloaderPlusService downloaderService;
    private final IPtDownloadRecordPlusService downloadRecordService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final DownloaderClientFactory clientFactory;

    public TorrentTransferService(IPtTransferRulePlusService ruleService,
                                  IPtTransferRecordPlusService recordService,
                                  IPtDownloaderPlusService downloaderService,
                                  IPtDownloadRecordPlusService downloadRecordService,
                                  IPtSubscriptionEpisodePlusService episodeService,
                                  DownloaderClientFactory clientFactory) {
        this.ruleService = ruleService;
        this.recordService = recordService;
        this.downloaderService = downloaderService;
        this.downloadRecordService = downloadRecordService;
        this.episodeService = episodeService;
        this.clientFactory = clientFactory;
    }

    /**
     * 跑一遍全部启用规则。定时任务的入口。
     * <p>
     * 单条规则失败只记 warn 并继续下一条：一台下载器连不上，不该让另一对下载器之间的
     * 转移也停摆。
     * </p>
     */
    public List<TransferSummary> transferAll() {
        List<TransferSummary> summaries = new ArrayList<>();
        for (PtTransferRulePlus rule : ruleService.listEnabled()) {
            try {
                summaries.add(runRule(rule));
            } catch (Exception e) {
                log.warn("转移规则[{}] 执行失败：{}", rule.getName(), e.getMessage());
            }
        }
        return summaries;
    }

    /**
     * 执行一条规则。手动触发也走这里。
     *
     * @throws Exception 下载器不存在或不可达
     */
    public TransferSummary runRule(PtTransferRulePlus rule) throws Exception {
        PtDownloaderPlus source = requireDownloader(rule.getSourceDownloaderId(), "源");
        PtDownloaderPlus target = requireDownloader(rule.getTargetDownloaderId(), "目标");
        if (Objects.equals(source.getId(), target.getId())) {
            throw new IllegalStateException("源下载器与目标下载器不能是同一个");
        }

        TransferSummary summary = new TransferSummary(rule.getName());
        IDownloaderClient sourceClient = clientFactory.get(source);
        IDownloaderClient targetClient = clientFactory.get(target);

        // 先推进上一轮留下的校验中记录，再发起新的转移。顺序不能反：这些记录占着
        // "同一个种子不重复发起"的名额，先把已经校验完的收掉，本轮的上限才用得到新种子上
        advanceVerifying(rule, source, target, sourceClient, targetClient, summary);

        if (!sourceClient.supportsExport()) {
            // 例如把 Transmission 配成了源。这不是"这个种子搬不了"而是"这条规则一个都搬不了"，
            // 逐个种子失败会刷出一屏一模一样的错误记录，不如整条规则报一次
            summary.setExportUnsupported(true);
            log.warn("转移规则[{}] 的源下载器[{}]不支持导出种子文件，本规则不会转移任何种子",
                    rule.getName(), source.getName());
            notifySummary(summary);
            return summary;
        }

        List<TransferCandidate> candidates = evaluate(rule, source, target, sourceClient, targetClient);
        summary.setScanned(candidates.size());
        for (TransferCandidate candidate : candidates) {
            if (!candidate.isTransferable()) {
                continue;
            }
            startTransfer(rule, source, target, sourceClient, targetClient, candidate, summary);
        }
        notifySummary(summary);
        return summary;
    }

    /**
     * 只判定不执行：列出源下载器上每个种子这一轮会不会被转移、不转移的原因是什么。
     * 预览接口直接用它。
     */
    public List<TransferCandidate> evaluate(PtTransferRulePlus rule) throws Exception {
        PtDownloaderPlus source = requireDownloader(rule.getSourceDownloaderId(), "源");
        PtDownloaderPlus target = requireDownloader(rule.getTargetDownloaderId(), "目标");
        return evaluate(rule, source, target, clientFactory.get(source), clientFactory.get(target));
    }

    private List<TransferCandidate> evaluate(PtTransferRulePlus rule, PtDownloaderPlus source,
                                             PtDownloaderPlus target, IDownloaderClient sourceClient,
                                             IDownloaderClient targetClient) throws Exception {
        List<DownloaderTorrent> torrents = sourceClient.listAll(source);
        // 目标端的 hash 全集拉一次就够，逐个种子去问一遍等于把目标端的种子列表拉 N 遍
        Set<String> targetHashes = new HashSet<>();
        for (DownloaderTorrent torrent : targetClient.listAll(target)) {
            if (StringUtils.isNotBlank(torrent.getHash())) {
                targetHashes.add(torrent.getHash().toLowerCase(Locale.ROOT));
            }
        }

        PathMapping mapping = PathMapping.parse(rule.getPathMapping());
        Set<String> includeTags = rule.includeTagSet();
        Set<String> excludeTags = rule.excludeTagSet();
        ProtectedTorrents hrProtected = ProtectedTorrents.of(loadHitAndRunPending());
        ProtectedTorrents uploadProtected = ProtectedTorrents.of(loadRecordsWithUnsettledEpisodes());
        Map<String, FailureStat> failures = loadFailureStats(rule.getId());

        List<TransferCandidate> candidates = new ArrayList<>();
        for (DownloaderTorrent torrent : torrents) {
            candidates.add(judge(rule, torrent, mapping, targetHashes, includeTags, excludeTags,
                    hrProtected, uploadProtected, failures));
        }
        // 体积从大到小：每轮有上限，先搬大的才能用最少的转移次数腾出最多空间，
        // 口径与自动删种一致
        candidates.sort(Comparator.comparingLong(TransferCandidate::sizeBytes).reversed());
        return applyRoundLimit(candidates, rule.roundLimit());
    }

    /**
     * 判定单个种子能不能转移。
     * <p>
     * 检查顺序按"越硬的护栏越靠前"排列，这样给出的原因是最有解释力的那一条——
     * 一个既在 H&R 考核期内、做种时长又不够的种子，告诉用户"H&R 未达标"比
     * "做种时长不够"更贴近他要做的事。
     * </p>
     */
    private TransferCandidate judge(PtTransferRulePlus rule, DownloaderTorrent torrent, PathMapping mapping,
                                    Set<String> targetHashes, Set<String> includeTags, Set<String> excludeTags,
                                    ProtectedTorrents hrProtected, ProtectedTorrents uploadProtected,
                                    Map<String, FailureStat> failures) {
        String targetPath = mapping.apply(torrent.getSavePath());
        String hash = torrent.getHash() == null ? "" : torrent.getHash().toLowerCase(Locale.ROOT);

        if (!torrent.isCompleted()) {
            return TransferCandidate.skip(torrent, TransferSkipReason.NOT_COMPLETED, targetPath);
        }
        if (isBusy(torrent)) {
            return TransferCandidate.skip(torrent, TransferSkipReason.BUSY_STATE, targetPath);
        }
        if (targetHashes.contains(hash)) {
            return TransferCandidate.skip(torrent, TransferSkipReason.ALREADY_ON_TARGET, targetPath);
        }
        if (recordService.hasVerifying(rule.getId(), hash)) {
            return TransferCandidate.skip(torrent, TransferSkipReason.IN_PROGRESS, targetPath);
        }
        if (hasAnyTag(torrent, excludeTags)) {
            return TransferCandidate.skip(torrent, TransferSkipReason.EXCLUDED_TAG, targetPath);
        }
        if (!includeTags.isEmpty() && !hasAnyTag(torrent, includeTags)) {
            return TransferCandidate.skip(torrent, TransferSkipReason.TAG_NOT_MATCHED, targetPath);
        }
        if (!rule.sizeMatches(torrent.getSize())) {
            return TransferCandidate.skip(torrent, TransferSkipReason.SIZE_NOT_MATCHED, targetPath);
        }
        if (torrent.getSeedingSeconds() < rule.minSeedSeconds()) {
            return TransferCandidate.skip(torrent, TransferSkipReason.SEED_TIME_NOT_REACHED, targetPath);
        }
        if (hrProtected.covers(torrent)) {
            return TransferCandidate.skip(torrent, TransferSkipReason.HIT_AND_RUN_PENDING, targetPath);
        }
        if (uploadProtected.covers(torrent)) {
            return TransferCandidate.skip(torrent, TransferSkipReason.EPISODE_UNSETTLED, targetPath);
        }
        // 失败历史放在最后判：这样列表里带「冷却中/失败过多」的都是本来会被转移的种子，
        // 不会出现"体积根本不符合规则、却报着失败次数"这种指错方向的原因
        TransferSkipReason retryBlock = retryBlockedBy(failures.get(hash));
        if (retryBlock != null) {
            return TransferCandidate.skip(torrent, retryBlock, targetPath);
        }
        return TransferCandidate.transferable(torrent, targetPath);
    }

    /**
     * 这个种子的失败历史是否挡住了本轮重试，挡住时返回对应的原因。
     * <p>
     * 没有这道闸门的话，任何一种<b>持续性</b>失败都会变成"每轮转移一次、每轮失败一次、
     * 每轮发一条通知"的死循环——失败不改变源端种子的状态，下一轮的判定条件与上一轮
     * 完全相同。踩过的具体案例：部分下载的种子转移后校验到不了 100%
     * （见 {@link #unwantedFileIndexes}），用户侧的现象就是同一个错误一直在报。
     * </p>
     */
    private TransferSkipReason retryBlockedBy(FailureStat stat) {
        if (stat == null) {
            return null;
        }
        if (stat.count() >= MAX_FAILURES_PER_TORRENT) {
            return TransferSkipReason.TOO_MANY_FAILURES;
        }
        if (stat.lastFinishMs() > 0
                && System.currentTimeMillis() - stat.lastFinishMs() < RETRY_COOLDOWN_MS) {
            return TransferSkipReason.RETRY_COOLDOWN;
        }
        return null;
    }

    /**
     * 本规则下每个种子的失败次数与最近一次失败时间。整规则查一次，不逐个种子去问——
     * 一台下载器上挂几百个种子是常态。
     */
    private Map<String, FailureStat> loadFailureStats(Integer ruleId) {
        if (ruleId == null) {
            return Map.of();
        }
        List<PtTransferRecordPlus> failed = recordService.list(new QueryWrapper<PtTransferRecordPlus>()
                .select("torrent_hash", "finish_time")
                .eq("rule_id", ruleId)
                .eq("state", TransferState.FAILED.value()));
        Map<String, FailureStat> stats = new HashMap<>();
        for (PtTransferRecordPlus record : failed) {
            String hash = record.getTorrentHash();
            if (StringUtils.isBlank(hash)) {
                continue;
            }
            long finish = record.getFinishTime() == null ? 0L : record.getFinishTime().getTime();
            stats.merge(hash.toLowerCase(Locale.ROOT), new FailureStat(1, finish),
                    (a, b) -> new FailureStat(a.count() + b.count(), Math.max(a.lastFinishMs(), b.lastFinishMs())));
        }
        return stats;
    }

    /** 某个种子在本规则下的失败次数与最近一次失败时间（毫秒，缺失为 0） */
    private record FailureStat(long count, long lastFinishMs) {
    }

    /**
     * 把超出本轮上限的可转移项改判成"留到下一轮"。
     * <p>
     * 上限作用在<b>可转移项</b>上而不是全部种子上：一台下载器可能挂着几百个种子，
     * 其中只有三五个达标，按全部种子数截断会让达标的因为排在后面而永远轮不上。
     * </p>
     */
    private List<TransferCandidate> applyRoundLimit(List<TransferCandidate> candidates, int limit) {
        int remaining = limit;
        int dropped = 0;
        List<TransferCandidate> result = new ArrayList<>(candidates.size());
        for (TransferCandidate candidate : candidates) {
            if (!candidate.isTransferable()) {
                result.add(candidate);
                continue;
            }
            if (remaining > 0) {
                remaining--;
                result.add(candidate);
            } else {
                dropped++;
                result.add(TransferCandidate.skip(candidate.getTorrent(), TransferSkipReason.ROUND_LIMIT,
                        candidate.getTargetSavePath()));
            }
        }
        if (dropped > 0) {
            // 静默截断会被读成"已经全搬完了"，必须说出来
            log.info("本轮转移上限 {} 已用尽，还有 {} 个达标的种子留到下一轮", limit, dropped);
        }
        return result;
    }

    /**
     * 发起一次转移：导出 → 暂停态加种 → 触发校验 → 落一条 VERIFYING 记录。
     * <p>
     * 这三步中任何一步失败都不留中间产物：加种之后失败会把刚加进去的种子撤掉
     * （不删文件），因为此刻能确定它是本次新加的。
     * </p>
     */
    private void startTransfer(PtTransferRulePlus rule, PtDownloaderPlus source, PtDownloaderPlus target,
                               IDownloaderClient sourceClient, IDownloaderClient targetClient,
                               TransferCandidate candidate, TransferSummary summary) {
        DownloaderTorrent torrent = candidate.getTorrent();
        String hash = torrent.getHash().toLowerCase(Locale.ROOT);
        PtTransferRecordPlus record = newRecord(rule, candidate);

        byte[] metainfo;
        try {
            metainfo = sourceClient.exportTorrent(source, hash);
        } catch (Exception e) {
            saveFailed(record, "导出种子文件失败：" + e.getMessage(), summary);
            return;
        }

        // 源端的文件选择必须在加种之前读出来：一旦源端种子在中途被删掉，这里失败还不留任何痕迹，
        // 而加种之后再失败就得回滚
        Set<Integer> unwanted;
        try {
            unwanted = unwantedFileIndexes(sourceClient, source, hash);
        } catch (Exception e) {
            saveFailed(record, "读取源端文件选择失败：" + e.getMessage(), summary);
            return;
        }

        AddTorrentOutcome outcome;
        try {
            outcome = targetClient.addTorrentFile(target, metainfo, candidate.getTargetSavePath(),
                    rule.getTargetTag(), true);
        } catch (Exception e) {
            saveFailed(record, "加入目标下载器失败：" + e.getMessage(), summary);
            return;
        }

        if (outcome == AddTorrentOutcome.DUPLICATE) {
            // 目标端本来就有这个种子（上一轮已经搬过、或用户自己加的）。绝不能撤销它，
            // 也没什么可做的，记一条 SKIPPED 让用户知道发生过什么即可
            record.setState(TransferState.SKIPPED.value());
            record.setFinishTime(new Date());
            recordService.save(record);
            summary.setSkipped(summary.getSkipped() + 1);
            return;
        }

        if (!unwanted.isEmpty()) {
            try {
                applyFileSelection(targetClient, target, hash, unwanted);
            } catch (Exception e) {
                // 选择没搬过去就校验，必然得到一个不到 100% 的进度、被判成「路径下没有这份数据」。
                // 种子还停在暂停态，撤掉它是安全的（deleteFiles=false）
                rollbackTarget(targetClient, target, hash);
                saveFailed(record, "同步源端的文件选择失败：" + e.getMessage(), summary);
                return;
            }
        }

        try {
            targetClient.recheckTorrent(target, hash);
        } catch (Exception e) {
            // 校验都没触发起来，种子还停在暂停态，撤掉它是安全的（deleteFiles=false）
            rollbackTarget(targetClient, target, hash);
            saveFailed(record, "触发目标端校验失败：" + e.getMessage(), summary);
            return;
        }

        record.setState(TransferState.VERIFYING.value());
        record.setVerifyStartTime(new Date());
        recordService.save(record);
        summary.setStarted(summary.getStarted() + 1);
        log.info("转移规则[{}] 已把种子「{}」加入下载器[{}]并开始校验{}", rule.getName(),
                torrent.getName(), target.getName(),
                unwanted.isEmpty() ? "" : "（已同步排除 " + unwanted.size() + " 个源端未选中的文件）");
    }

    /**
     * 读出源端种子里<b>没被选中下载</b>的文件序号。
     * <p>
     * 这是「部分下载的种子转移后校验永远到不了 100%」的修复点。qBittorrent 的
     * {@code progress} 是相对<b>已选文件</b>算的，源端一个只下了其中几集的种子照样显示
     * 100%、照样满足转移条件；而 {@code exportTorrent} 导出的 .torrent 里<b>不含</b>
     * 文件优先级，原样加到目标端就是全选，校验后进度必然不到 1，被
     * {@link #advanceVerifying} 判成「该路径下没有这份数据」，回滚、失败、发通知，
     * 下一轮再来一遍——源端种子条件没变，这个循环不会自己停。
     * </p>
     * <p>
     * 这在本项目里不是边缘情况：OSR 自己给季包排除非目标集文件
     * （{@code IDownloaderClient#excludeFiles}），下载器里留下的正是这种部分下载的种子。
     * </p>
     * <p>
     * 文件列表读不出来时<b>直接失败</b>而不是当作全选继续：这时候的实际情况只有两种——
     * 种子已经不在源端（那样加到目标端只会得到一个校验不过的任务），或者下载器这一刻答不了
     * （留到下一轮重试即可）。两种都不该硬着头皮往下走。
     * </p>
     */
    private Set<Integer> unwantedFileIndexes(IDownloaderClient sourceClient, PtDownloaderPlus source, String hash)
            throws Exception {
        List<DownloaderTorrentFile> files = sourceClient.listFiles(source, hash);
        if (files.isEmpty()) {
            throw new IllegalStateException("源下载器返回的文件列表为空，种子可能已被移除");
        }
        Set<Integer> unwanted = new HashSet<>();
        for (DownloaderTorrentFile file : files) {
            if (!file.isWanted()) {
                unwanted.add(file.getIndex());
            }
        }
        if (unwanted.size() == files.size()) {
            // 一个文件都不选，加过去也是个空壳。真出现只可能是 wanted 解析反了，宁可整次失败
            throw new IllegalStateException("源端种子的文件全部处于未选中状态，不予转移");
        }
        return unwanted;
    }

    /**
     * 把源端的「不下载」选择应用到刚加进目标端的种子上。
     * <p>
     * 加种接口返回成功不等于种子已经在下载器的列表里可见（qB 的
     * {@code /torrents/add} 是异步落库的），此刻直接设文件优先级会得到 404/409。
     * 因此先轮询等它出现——等不到就抛异常，由调用方回滚。
     * </p>
     */
    private void applyFileSelection(IDownloaderClient targetClient, PtDownloaderPlus target, String hash,
                                    Set<Integer> unwanted) throws Exception {
        awaitTorrentVisible(targetClient, target, hash);
        targetClient.excludeFiles(target, hash, unwanted);
    }

    /** 加种之后等种子在目标端可见，最多等 {@link #VISIBILITY_ATTEMPTS} 次、每次间隔 1 秒 */
    private void awaitTorrentVisible(IDownloaderClient targetClient, PtDownloaderPlus target, String hash)
            throws Exception {
        for (int attempt = 1; attempt <= VISIBILITY_ATTEMPTS; attempt++) {
            if (targetClient.getTorrent(target, hash) != null) {
                return;
            }
            Thread.sleep(VISIBILITY_INTERVAL_MS);
        }
        throw new IllegalStateException("加种后 " + (VISIBILITY_ATTEMPTS * VISIBILITY_INTERVAL_MS / 1000)
                + " 秒内目标下载器仍看不到该种子");
    }

    /**
     * 推进上一轮留下的校验中记录。
     * <p>
     * 四种结果各自的处置：目标端找不到种子 → 失败（无可回滚）；校验通过 → 启动做种、
     * 按规则删源端种子；仍在校验 → 超时则回滚，否则留到下一轮；校验结束但进度不足 →
     * 回滚并把实际进度写进失败原因（这是路径映射配错时唯一有诊断价值的信息）。
     * </p>
     */
    private void advanceVerifying(PtTransferRulePlus rule, PtDownloaderPlus source, PtDownloaderPlus target,
                                  IDownloaderClient sourceClient, IDownloaderClient targetClient,
                                  TransferSummary summary) {
        for (PtTransferRecordPlus record : recordService.listVerifying(rule.getId())) {
            String hash = record.getTorrentHash();
            DownloaderTorrent torrent;
            try {
                torrent = targetClient.getTorrent(target, hash);
            } catch (Exception e) {
                // 目标端这一轮问不到（网络抖动、下载器重启中）。不下结论，留到下一轮——
                // 把"问不到"判成失败会在一次抖动里回滚掉一批本来正常的转移
                log.warn("转移规则[{}] 查询目标端种子[{}]失败，本轮不处理：{}", rule.getName(), hash, e.getMessage());
                continue;
            }

            if (torrent == null) {
                markFailed(record, "目标下载器上找不到该种子（可能已被手动移除）", summary);
                continue;
            }
            if (torrent.isCompleted()) {
                finishTransfer(rule, source, target, sourceClient, targetClient, record, torrent, summary);
                continue;
            }
            if (torrent.isChecking()) {
                if (verifyTimedOut(record, rule)) {
                    rollbackTarget(targetClient, target, hash);
                    markFailed(record, "目标端校验超时（超过 " + rule.getVerifyTimeoutMinutes() + " 分钟）", summary);
                }
                continue;
            }
            // 校验跑完了却没到 100%：目标下载器在它看到的保存路径下找不到这份数据
            rollbackTarget(targetClient, target, hash);
            markFailed(record, String.format(
                    "目标端校验后进度只有 %.1f%%，说明该路径下没有这份数据，请检查两个下载器的保存路径"
                            + "（目标路径：%s）", torrent.getProgress() * 100, record.getTargetSavePath()), summary);
        }
    }

    /**
     * 收尾一次成功的转移：启动目标端做种 → 按规则删源端种子 → 把下载记录改挂到新下载器。
     * <p>
     * 顺序是硬要求：<b>先启动目标端，再删源端</b>。反过来的话，中间那段窗口里这份数据
     * 在两台机器上都不在做种，站点看到的是一个突然消失的种子。
     * </p>
     */
    private void finishTransfer(PtTransferRulePlus rule, PtDownloaderPlus source, PtDownloaderPlus target,
                                IDownloaderClient sourceClient, IDownloaderClient targetClient,
                                PtTransferRecordPlus record, DownloaderTorrent torrent, TransferSummary summary) {
        String hash = record.getTorrentHash();
        try {
            targetClient.resumeTorrent(target, hash);
        } catch (Exception e) {
            // 启动失败就停在这里，绝不往下走去删源端种子——那会让这份数据两边都不做种。
            // 记录保持 VERIFYING，下一轮会重来（种子已经是 100%，重来只是再启动一次）
            log.warn("转移规则[{}] 启动目标端种子[{}]失败，源端种子保持不动，下一轮重试：{}",
                    rule.getName(), hash, e.getMessage());
            return;
        }

        if (rule.deleteSourceOn()) {
            try {
                // deleteFiles 恒为 false：那份文件正是目标端刚接手做种的数据
                sourceClient.deleteTorrent(source, hash, false);
                record.setSourceDeleted("1");
            } catch (Exception e) {
                // 源端没删掉不影响"转移成功"这个事实——数据已经在目标端做种了。
                // 留下一个重复做种的种子比让整次转移失败要好，用户手动删掉即可
                log.warn("转移规则[{}] 删除源端种子[{}]失败，需要手动处理（目标端已正常做种）：{}",
                        rule.getName(), hash, e.getMessage());
            }
        }

        record.setState(TransferState.COMPLETED.value());
        record.setFinishTime(new Date());
        recordService.updateById(record);
        retargetDownloadRecord(hash, target.getId());

        summary.setCompleted(summary.getCompleted() + 1);
        summary.setCompletedBytes(summary.getCompletedBytes() + Math.max(0L, torrent.getSize()));
        log.info("转移规则[{}] 种子「{}」已在下载器[{}]接手做种{}", rule.getName(), record.getTorrentName(),
                target.getName(), record.sourceDeletedOn() ? "，源端种子已移除（文件保留）" : "");
    }

    /**
     * 把下载记录改挂到新的下载器。
     * <p>
     * 不改也能自愈——{@code DownloadTrackService#trackSeeding} 在本下载器找不到种子时
     * 会去其余快照里找一遍并改写 downloader_id。但那要等下一次追踪轮询，而且那条路径
     * 走的是弱匹配（标签/种子名）。这里 hash 是确定的，顺手改掉最准。
     * </p>
     */
    private void retargetDownloadRecord(String hash, Integer targetDownloaderId) {
        try {
            downloadRecordService.update(new UpdateWrapper<PtDownloadRecordPlus>()
                    .eq("torrent_hash", hash)
                    .set("downloader_id", targetDownloaderId));
        } catch (Exception e) {
            log.debug("改写下载记录的下载器归属失败（追踪侧会自愈，不影响转移结果）：{}", e.getMessage());
        }
    }

    /**
     * 撤销目标端的种子。<b>deleteFiles 恒为 false。</b>
     * <p>
     * 这里删的是"刚加进去、还没开始做种"的种子，而那份文件是源下载器正在做种的数据。
     * 传 true 会让源端种子立刻变成"文件丢失"，在它所属的站点上记一次 H&R——
     * 这是整个功能里唯一能造成真实数据损失的地方。
     * </p>
     */
    private void rollbackTarget(IDownloaderClient targetClient, PtDownloaderPlus target, String hash) {
        try {
            targetClient.deleteTorrent(target, hash, false);
            log.info("已撤销目标下载器[{}]上的种子[{}]（文件未删除）", target.getName(), hash);
        } catch (Exception e) {
            log.warn("撤销目标下载器[{}]上的种子[{}]失败，那里可能留下一个暂停的种子，需要手动清理：{}",
                    target.getName(), hash, e.getMessage());
        }
    }

    private PtTransferRecordPlus newRecord(PtTransferRulePlus rule, TransferCandidate candidate) {
        DownloaderTorrent torrent = candidate.getTorrent();
        PtTransferRecordPlus record = new PtTransferRecordPlus();
        record.setRuleId(rule.getId());
        record.setTorrentHash(torrent.getHash().toLowerCase(Locale.ROOT));
        record.setTorrentName(torrent.getName());
        record.setSizeBytes(Math.max(0L, torrent.getSize()));
        record.setSourceDownloaderId(rule.getSourceDownloaderId());
        record.setTargetDownloaderId(rule.getTargetDownloaderId());
        record.setSourceSavePath(torrent.getSavePath());
        record.setTargetSavePath(candidate.getTargetSavePath());
        record.setSourceDeleted("0");
        return record;
    }

    /** 记录还没落库时的失败：直接以 FAILED 状态存一条，用户能在记录页看到发生过什么 */
    private void saveFailed(PtTransferRecordPlus record, String reason, TransferSummary summary) {
        record.setState(TransferState.FAILED.value());
        record.setFailReason(truncate(reason));
        record.setFinishTime(new Date());
        recordService.save(record);
        summary.setFailed(summary.getFailed() + 1);
        log.warn("转移失败：种子「{}」{}", record.getTorrentName(), reason);
    }

    /** 已落库记录的失败 */
    private void markFailed(PtTransferRecordPlus record, String reason, TransferSummary summary) {
        record.setState(TransferState.FAILED.value());
        record.setFailReason(truncate(reason));
        record.setFinishTime(new Date());
        recordService.updateById(record);
        summary.setFailed(summary.getFailed() + 1);
        log.warn("转移失败：种子「{}」{}", record.getTorrentName(), reason);
    }

    /** fail_reason 列宽 512，超长会让整条 UPDATE 失败，那才是真正丢信息 */
    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500) + "...";
    }

    private boolean verifyTimedOut(PtTransferRecordPlus record, PtTransferRulePlus rule) {
        Date start = record.getVerifyStartTime();
        if (start == null) {
            // 开始时间缺失（历史数据或写入异常）时不判超时：宁可多等一轮，也不要凭空回滚
            return false;
        }
        long elapsed = (System.currentTimeMillis() - start.getTime()) / 1000;
        return elapsed > rule.verifyTimeoutSeconds();
    }

    private boolean isBusy(DownloaderTorrent torrent) {
        String state = torrent.getRawState();
        if (StringUtils.isBlank(state)) {
            // 状态读不出来时按"忙"处理：判据缺失时不动手
            return true;
        }
        String normalized = state.toLowerCase(Locale.ROOT);
        return QB_BUSY_STATES.contains(normalized) || TR_BUSY_STATES.contains(normalized);
    }

    private boolean hasAnyTag(DownloaderTorrent torrent, Set<String> tags) {
        if (tags.isEmpty() || StringUtils.isBlank(torrent.getTags())) {
            return false;
        }
        for (String tag : torrent.getTags().split(",")) {
            if (tags.contains(tag.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private PtDownloaderPlus requireDownloader(Integer id, String role) {
        PtDownloaderPlus downloader = id == null ? null : downloaderService.getById(id);
        if (downloader == null) {
            throw new IllegalStateException(role + "下载器不存在（id=" + id + "）");
        }
        return downloader;
    }

    /**
     * 仍在 H&R 考核中的下载记录。查询口径与 {@code TorrentCleanService} 的同名方法一致，
     * 都<b>不限下载器</b>——种子可能已经被搬到别的机器上，而记录里的 downloader_id 未必跟上。
     */
    private List<PtDownloadRecordPlus> loadHitAndRunPending() {
        return downloadRecordService.list(new QueryWrapper<PtDownloadRecordPlus>()
                .eq("hr_state", HitAndRunState.PENDING.value()));
    }

    /**
     * 还有集停在 IN_FLIGHT/UPGRADING 的下载记录：种子下完了，但文件还没传完网盘。
     * 查询口径与 {@code TorrentCleanService} 的同名方法一致。
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
        return downloadRecordService.listByIds(recordIds);
    }

    /**
     * 汇总通知。
     * <p>
     * 只在真有事情发生时发（发起/完成/失败任一非零）。多数轮次什么都不会发生，
     * 逐轮发一条"本轮转移了 0 个"等于刷屏——这一点在 RSS 轮询的汇总通知上已经栽过一次。
     * </p>
     */
    private void notifySummary(TransferSummary summary) {
        if (summary.isExportUnsupported()) {
            sendSafely("⚠️ 转移做种：规则 " + StringUtils.escapeHtml(summary.getRuleName())
                    + "\n源下载器不支持导出种子文件，本规则不会转移任何种子。"
                    + "\nTransmission 只能作为转移的目标，不能作为来源。");
            return;
        }
        if (!summary.worthNotifying()) {
            return;
        }
        StringBuilder msg = new StringBuilder("🔄 转移做种：规则 ")
                .append(StringUtils.escapeHtml(summary.getRuleName()));
        if (summary.getStarted() > 0) {
            msg.append("\n本轮发起 ").append(summary.getStarted()).append(" 个转移（目标端校验中）");
        }
        if (summary.getCompleted() > 0) {
            msg.append("\n已完成 ").append(summary.getCompleted()).append(" 个，共 ")
                    .append(summary.completedSizeText());
        }
        if (summary.getFailed() > 0) {
            msg.append("\n⚠️ ").append(summary.getFailed()).append(" 个失败，详情见转移记录");
        }
        sendSafely(msg.toString());
    }

    private void sendSafely(String message) {
        try {
            TgHelper.sendMsg(NotificationType.GENERAL, message);
        } catch (Exception e) {
            log.debug("转移做种通知发送失败（不影响转移结果）：{}", e.getMessage());
        }
    }
}
