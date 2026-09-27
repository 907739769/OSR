package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.task.dto.DownloadDeleteResult;
import com.osr.openliststrm.pt.task.dto.DownloadLiveView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 下载记录页上对下载器里那个种子的直接操作：实时速度、暂停 / 继续、删除下载。
 * <p>
 * 与 {@link DownloadRecordAdminService} 分开：那边只动 OSR 自己的数据（重试、忽略、拉黑、清理），
 * 这边每个动作都要现连一次下载器，失败模式（下载器离线、种子不见了）完全不同。
 * </p>
 * <p>
 * <b>删除是「OSR 从不删种」的第三个受控例外</b>（另两个见 {@code pt/downloader/AGENTS.md}），
 * 边界由 {@link #deleteTorrent} 的四条检查守着：只有用户逐条手动发起，且 H&R 考核中的一律拒绝。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class DownloadRecordControlService {

    private static final String STATE_PUSHED = DownloadRecordState.PUSHED.value();
    private static final String STATE_DOWNLOADING = DownloadRecordState.DOWNLOADING.value();

    /** 一次实时查询最多带多少条：页面一页最多几十张卡片，给个上限挡住手工拼的超长请求 */
    public static final int LIVE_MAX_IDS = 100;

    private final IPtDownloadRecordPlusService recordService;
    private final IPtDownloaderPlusService downloaderService;
    private final IPtIndexerPlusService indexerService;
    private final DownloaderClientFactory downloaderClientFactory;
    private final DownloadTrackService trackService;

    public DownloadRecordControlService(IPtDownloadRecordPlusService recordService,
                                        IPtDownloaderPlusService downloaderService,
                                        IPtIndexerPlusService indexerService,
                                        DownloaderClientFactory downloaderClientFactory,
                                        DownloadTrackService trackService) {
        this.recordService = recordService;
        this.downloaderService = downloaderService;
        this.indexerService = indexerService;
        this.downloaderClientFactory = downloaderClientFactory;
        this.trackService = trackService;
    }

    // ---------- 实时状态 ----------

    /**
     * 查一批在途记录此刻的速度与进度。只处理 PUSHED/DOWNLOADING 的记录，其余 id 直接不返回。
     * <p>
     * 按下载器分组、每台只拉一次公共标签下的种子列表，而不是逐条 {@code getTorrent}：
     * 一页十几条在途记录逐条问就是十几次往返，而页面每几秒就要来一轮。
     * 某台下载器连不上时，它名下的记录报 {@code found=false}，不影响其余下载器，也不抛给前端——
     * 页面只是少显示几个速度，下载器离线的告警由下载追踪与首页待办负责。
     * </p>
     */
    public List<DownloadLiveView> live(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<PtDownloadRecordPlus> active = recordService.listByIds(ids).stream()
                .filter(r -> STATE_PUSHED.equals(r.getState()) || STATE_DOWNLOADING.equals(r.getState()))
                .filter(r -> r.getDownloaderId() != null)
                .toList();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<PtDownloadRecordPlus>> byDownloader = active.stream()
                .collect(Collectors.groupingBy(PtDownloadRecordPlus::getDownloaderId));
        Map<Integer, PtDownloaderPlus> downloaders = downloaderService.listByIds(byDownloader.keySet()).stream()
                .collect(Collectors.toMap(PtDownloaderPlus::getId, d -> d));

        List<DownloadLiveView> result = new ArrayList<>();
        byDownloader.forEach((downloaderId, records) -> {
            List<DownloaderTorrent> torrents = fetchForLive(downloaders.get(downloaderId));
            for (PtDownloadRecordPlus record : records) {
                result.add(toLive(record, findByTag(torrents, record.getTrackingTag())));
            }
        });
        return result;
    }

    /** 拉一台下载器上 OSR 推送的种子；下载器不存在或连不上时返回空列表（这批记录都报 found=false） */
    private List<DownloaderTorrent> fetchForLive(PtDownloaderPlus downloader) {
        if (downloader == null) {
            return List.of();
        }
        try {
            IDownloaderClient client = downloaderClientFactory.get(downloader);
            // 与下载追踪同一口径：在途记录一律推在下载池那几台上，按公共标签拉即可
            return StringUtils.isBlank(downloader.getTag())
                    ? client.listAll(downloader)
                    : client.listByTag(downloader, downloader.getTag());
        } catch (Exception e) {
            // 页面每几秒一轮，下载器离线时这里会一直失败；离线本身由 DownloadTrackTask 按故障起止记 WARN，
            // 这里再记 WARN 就是逐条复读，只留 DEBUG
            log.debug("实时查询下载器[{}]失败：{}", downloader.getName(), e.getMessage());
            return List.of();
        }
    }

    private DownloadLiveView toLive(PtDownloadRecordPlus record, DownloaderTorrent torrent) {
        DownloadLiveView view = new DownloadLiveView();
        view.setId(record.getId());
        if (torrent == null) {
            view.setFound(false);
            return view;
        }
        view.setFound(true);
        view.setDownloadSpeed(torrent.getDownloadSpeed());
        view.setUploadSpeed(torrent.getUploadSpeed());
        view.setEtaSeconds(torrent.getEtaSeconds());
        view.setProgress(torrent.getProgress());
        view.setPaused(torrent.isPaused());
        return view;
    }

    // ---------- 暂停 / 继续 ----------

    /**
     * 暂停一个下载中的种子，并在记录上打「用户暂停」标记（下载追踪据此停掉僵尸超时的计时，
     * 也不再在选完文件后自动启动它）。
     * <p>
     * 只允许 DOWNLOADING：已推送的多集包本来就是暂停态、在等选文件，再暂停没有意义；
     * 已完成的种子暂停就是停止做种，H&R 考核的做种时长会跟着停。后者还要看下载器里的<b>实时</b>进度——
     * 落库的状态最多落后 30 秒，用户在 99.9% 时点暂停，那一刻种子可能已经下完、正在做种。
     * </p>
     */
    public void pause(Integer recordId) {
        PtDownloadRecordPlus record = requireRecord(recordId);
        if (!STATE_DOWNLOADING.equals(record.getState())) {
            throw new IllegalArgumentException("只有下载中的记录可以暂停");
        }
        if (DownloadTrackService.isUserPaused(record)) {
            throw new IllegalArgumentException("这个下载已经暂停了");
        }
        PtDownloaderPlus downloader = requireDownloader(record);
        IDownloaderClient client = downloaderClientFactory.get(downloader);
        DownloaderTorrent torrent = requireTorrent(downloader, record);
        if (torrent.isCompleted()) {
            throw new IllegalArgumentException("种子已下载完成，暂停会中断做种，已取消操作");
        }
        callDownloader(downloader, "暂停", () -> client.pauseTorrent(downloader, torrent.getHash()));

        PtDownloadRecordPlus set = new PtDownloadRecordPlus();
        set.setUserPausedTime(new Date());
        boolean marked = recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .eq("state", STATE_DOWNLOADING)
                .isNull("user_paused_time"));
        if (!marked) {
            // 这一瞬间记录被追踪轮次改了状态（下完了 / 判失败了）：把刚才的暂停撤回，不留一个
            // 没有标记、却停在下载器里的种子——那样它既不会被自动启动，僵尸超时也照样计时
            tryResume(client, downloader, torrent.getHash());
            throw new IllegalArgumentException("下载状态刚刚发生了变化，请刷新后再试");
        }
        log.info("下载记录[{}] 已被用户暂停：{}", record.getId(), record.getTitle());
    }

    /** 继续一个被用户暂停的下载：启动种子，并把本次暂停时长并入累计（僵尸超时扣除用） */
    public void resume(Integer recordId) {
        PtDownloadRecordPlus record = requireRecord(recordId);
        if (!DownloadTrackService.isUserPaused(record)) {
            throw new IllegalArgumentException("这个下载没有处于暂停状态");
        }
        PtDownloaderPlus downloader = requireDownloader(record);
        IDownloaderClient client = downloaderClientFactory.get(downloader);
        DownloaderTorrent torrent = requireTorrent(downloader, record);
        callDownloader(downloader, "继续", () -> client.resumeTorrent(downloader, torrent.getHash()));
        DownloadTrackService.clearUserPause(recordService, record, System.currentTimeMillis());
        log.info("下载记录[{}] 已被用户继续下载：{}", record.getId(), record.getTitle());
    }

    private void tryResume(IDownloaderClient client, PtDownloaderPlus downloader, String hash) {
        try {
            client.resumeTorrent(downloader, hash);
        } catch (Exception e) {
            log.warn("撤回暂停失败，种子[{}]可能停在下载器[{}]里，需要手动继续：{}",
                    hash, downloader.getName(), e.getMessage(), e);
        }
    }

    // ---------- 删除 ----------

    /**
     * 删除这条记录对应的种子。四条检查缺一不可：
     * <ol>
     *   <li><b>H&R 考核中一律拒绝</b>（{@code hr_state} 非空且不是已达标）。这是用户定的硬边界：
     *       考核期内删种等于一点就被站点记一次 H&R。</li>
     *   <li><b>站点开了 H&R、种子已下完、却没有达标记录的也拒绝</b>。落库的 hr_state 最多落后 30 秒，
     *       而且站点可能是后来才开的 H&R——这两种情况下没有任何证据证明它已经安全。</li>
     *   <li><b>已下载完成的种子只移除任务、不删文件</b>。文件可能正被媒体库 / STRM 引用，
     *       也可能被 IYUU 辅种的兄弟种子共用；删掉它们的代价远大于留着。</li>
     *   <li><b>只按跟踪标签与 hash 认种子，不按种子名</b>。下载追踪在保种阶段会退到按名字匹配，
     *       但辅种的兄弟种子名字往往逐字一致，按名字删就可能删错一个。</li>
     * </ol>
     * 在途记录删完转 FAILED（{@link FailReasonCode#USER_DELETED}），关联集退回缺失；
     * 已完成 / 已失败的记录只动下载器，记录本身不变。
     */
    public DownloadDeleteResult deleteTorrent(Integer recordId, boolean deleteFiles) {
        PtDownloadRecordPlus record = requireRecord(recordId);
        String hrState = record.getHrState();
        if (hrState != null && !HitAndRunState.SATISFIED.value().equals(hrState)) {
            throw new IllegalArgumentException(HitAndRunState.PENDING.value().equals(hrState)
                    ? "该种子正在 H&R 保种考核中，达标前不能删除"
                    : "该种子的 H&R 考核没有达标记录，不能在这里删除，请到下载器里自行确认");
        }
        PtDownloaderPlus downloader = requireDownloader(record);
        DownloaderTorrent torrent = requireTorrent(downloader, record);
        boolean started = torrent.isCompleted() || torrent.getSeedingSeconds() > 0;
        if (started && !HitAndRunState.SATISFIED.value().equals(hrState) && hitAndRunEnabled(record)) {
            throw new IllegalArgumentException("来源站点开启了 H&R 考核，这个种子已下载完成但没有达标记录，"
                    + "无法确认能否安全删除，请到下载器里自行处理");
        }
        if (deleteFiles && started) {
            throw new IllegalArgumentException("已下载完成的种子只能移除任务、不能删除文件：媒体库或 STRM 可能正在使用这些文件");
        }

        IDownloaderClient client = downloaderClientFactory.get(downloader);
        callDownloader(downloader, "删除", () -> client.deleteTorrent(downloader, torrent.getHash(), deleteFiles));
        log.info("下载记录[{}] 的种子已被用户从下载器[{}]删除{}：{}", record.getId(), downloader.getName(),
                deleteFiles ? "（含已下载文件）" : "", record.getTitle());

        boolean recordFailed = false;
        if (STATE_PUSHED.equals(record.getState()) || STATE_DOWNLOADING.equals(record.getState())) {
            // 先删种、后改记录：反过来的话删种失败会留下一条「已失败」却还在下载器里跑的种子，
            // 没人再管它。这个顺序下崩在中间，下一轮追踪会发现种子没了、按「种子丢失」收尾
            recordFailed = trackService.failByUser(record);
        }
        return new DownloadDeleteResult(deleteFiles, recordFailed);
    }

    private boolean hitAndRunEnabled(PtDownloadRecordPlus record) {
        if (record.getIndexerId() == null) {
            return false;
        }
        PtIndexerPlus indexer = indexerService.getById(record.getIndexerId());
        return indexer != null && indexer.hitAndRunEnabled();
    }

    // ---------- 公共 ----------

    private PtDownloadRecordPlus requireRecord(Integer recordId) {
        PtDownloadRecordPlus record = recordService.getById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("下载记录不存在：" + recordId);
        }
        return record;
    }

    private PtDownloaderPlus requireDownloader(PtDownloadRecordPlus record) {
        PtDownloaderPlus downloader = record.getDownloaderId() == null
                ? null : downloaderService.getById(record.getDownloaderId());
        if (downloader == null) {
            throw new IllegalArgumentException("这条记录关联的下载器已不存在");
        }
        return downloader;
    }

    /**
     * 在下载器里找到这条记录的种子：先按跟踪标签（OSR 推送时打的、一条记录一个），
     * 找不到再按 hash（IYUU 转移可能丢标签）。<b>不按种子名找</b>，理由见 {@link #deleteTorrent}。
     */
    private DownloaderTorrent requireTorrent(PtDownloaderPlus downloader, PtDownloadRecordPlus record) {
        IDownloaderClient client = downloaderClientFactory.get(downloader);
        DownloaderTorrent found;
        try {
            found = StringUtils.isBlank(record.getTrackingTag())
                    ? null : findByTag(client.listByTag(downloader, record.getTrackingTag()), record.getTrackingTag());
            if (found == null && StringUtils.isNotBlank(record.getTorrentHash())) {
                found = client.getTorrent(downloader, record.getTorrentHash().toLowerCase());
            }
        } catch (IOException e) {
            log.warn("查询下载器[{}]中下载记录[{}]的种子失败：{}", downloader.getName(), record.getId(), e.getMessage(), e);
            throw new IllegalArgumentException("连接下载器「" + downloader.getName() + "」失败：" + e.getMessage());
        }
        if (found == null || StringUtils.isBlank(found.getHash())) {
            throw new IllegalArgumentException("下载器「" + downloader.getName() + "」里已找不到这个种子");
        }
        return found;
    }

    /** 调下载器，IO 异常转成带下载器名字的业务提示；原因与堆栈留在日志里 */
    private void callDownloader(PtDownloaderPlus downloader, String action, DownloaderCall call) {
        try {
            call.run();
        } catch (IOException e) {
            log.warn("下载器[{}]{}种子失败：{}", downloader.getName(), action, e.getMessage(), e);
            throw new IllegalArgumentException("下载器「" + downloader.getName() + "」" + action + "失败：" + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface DownloaderCall {
        void run() throws IOException;
    }

    /** 按跟踪标签认种子，口径与 {@code DownloadTrackService#findByTag} 一致（标签逗号分隔、逐个精确比对） */
    static DownloaderTorrent findByTag(List<DownloaderTorrent> torrents, String trackingTag) {
        if (StringUtils.isBlank(trackingTag) || torrents == null) {
            return null;
        }
        for (DownloaderTorrent torrent : torrents) {
            if (StringUtils.isBlank(torrent.getTags())) {
                continue;
            }
            for (String tag : torrent.getTags().split(",")) {
                if (trackingTag.equals(tag.trim())) {
                    return torrent;
                }
            }
        }
        return null;
    }
}
