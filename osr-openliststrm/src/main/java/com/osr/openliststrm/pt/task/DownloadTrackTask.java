package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.FaultThrottle;
import com.osr.common.utils.Threads;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每 30 秒轮询下载器，把种子状态回映到下载记录。
 *
 * @author Jack
 */
@Slf4j
@Component
public class DownloadTrackTask {

    @Autowired
    private IPtDownloaderPlusService downloaderService;
    @Autowired
    private DownloaderClientFactory downloaderClientFactory;
    @Autowired
    private DownloadTrackService trackService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复轮询所有下载器 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * 故障告警节流。<b>作为单例 bean 的实例字段是正确的</b>：它存的是需要跨轮次存活的
     * 组件级状态，与「@Component 是单例、不能用实例字段存<b>单次请求</b>状态」那条规矩
     * 说的不是一回事——放进方法局部变量的话每轮都重新开始，节流就不存在了。
     */
    private final FaultThrottle faultThrottle = new FaultThrottle();


    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(30), Duration.ofSeconds(30));
        log.info("DownloadTrackTask started");
    }

    @PreDestroy
    public void stop() {
        log.info("DownloadTrackTask stopped");
        MDC.clear();
    }

    /**
     * 拉取一个下载器的种子列表。
     * <p>
     * 仅做种的下载器拉<b>全量</b>：它上面的种子是 IYUU 转移/辅种加进来的，不带 OSR 的标签，
     * 按标签查一条都看不见——而 H&R 追踪恰恰要在这里找回被转移走的种子。
     * 参与订阅下载的下载器仍按标签查，避免把用户手工添加的一大堆无关种子拉进来。
     * </p>
     */
    private List<DownloaderTorrent> fetchTorrents(PtDownloaderPlus downloader) throws Exception {
        IDownloaderClient client = downloaderClientFactory.get(downloader);
        return downloader.participatesInDownload()
                ? client.listByTag(downloader, downloader.getTag())
                : client.listAll(downloader);
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("DownloadTrackTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            List<PtDownloaderPlus> downloaders = downloaderService.list(
                    new QueryWrapper<PtDownloaderPlus>().eq("enabled", "1"));
            // 先把所有下载器的快照拉全，再逐个推进状态：H&R 追踪要靠"别的下载器里有没有这个种子"
            // 才能把 IYUU 转移与"种子被删了"区分开，边拉边处理的话先处理的那些拿不到后面的快照
            List<DownloaderSnapshot> snapshots = new ArrayList<>();
            for (PtDownloaderPlus downloader : downloaders) {
                String key = "fetch:" + downloader.getId();
                try {
                    snapshots.add(new DownloaderSnapshot(downloader, fetchTorrents(downloader)));
                    if (faultThrottle.onSuccess(key)) {
                        log.info("下载器[{}]已恢复，种子列表拉取正常", downloader.getName());
                    }
                } catch (Exception e) {
                    // 本任务每 30 秒一轮：下载器离线时原先是每天 2880 条逐字相同的 WARN，
                    // 既淹掉别的日志，又不比第一条多告诉你任何事。只在故障开始/恢复时喊。
                    FaultThrottle.Decision d = faultThrottle.onFailure(key);
                    if (d.shouldReport()) {
                        log.warn("拉取下载器[{}]种子列表失败（连续第 {} 次）：{}",
                                downloader.getName(), d.consecutiveFailures(), e.getMessage());
                    } else {
                        log.debug("拉取下载器[{}]种子列表仍在失败（连续第 {} 次）：{}",
                                downloader.getName(), d.consecutiveFailures(), e.getMessage());
                    }
                }
            }
            for (DownloaderSnapshot snapshot : snapshots) {
                String name = snapshot.downloader().getName();
                String key = "track:" + snapshot.downloader().getId();
                try {
                    trackService.track(snapshot.downloader(), snapshot.torrents(), snapshots);
                    if (faultThrottle.onSuccess(key)) {
                        log.info("下载器[{}]追踪已恢复", name);
                    }
                } catch (Exception e) {
                    FaultThrottle.Decision d = faultThrottle.onFailure(key);
                    if (d.shouldReport()) {
                        log.warn("追踪下载器[{}]失败（连续第 {} 次）：{}", name, d.consecutiveFailures(), e.getMessage());
                    } else {
                        log.debug("追踪下载器[{}]仍在失败（连续第 {} 次）：{}", name, d.consecutiveFailures(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("DownloadTrackTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
