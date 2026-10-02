package com.osr.openliststrm.pt.downloader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.FaultThrottle;
import com.osr.common.utils.LogOnce;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.notify.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 读各下载器的剩余空间，低于告警线时提醒。
 * <p>
 * 磁盘写满时下载器的表现是任务卡住或报错，OSR 这边只看得到「下载失败」，原因要用户自己去下载器里翻；
 * 而它是提前看得见的。提醒只在三个时刻发：<b>刚低于告警线、持续低位每满一天、回到告警线以上</b>——
 * 15 分钟一轮，每轮都发就是一天 96 条逐字相同的消息。
 * </p>
 */
@Slf4j
@Service
public class DownloaderSpaceService {

    /** 持续低位时多久再提醒一次 */
    static final Duration REMIND_EVERY = Duration.ofHours(24);

    private final IPtDownloaderPlusService downloaderService;
    private final DownloaderClientFactory clientFactory;
    private final DownloaderSpaceRegistry registry;

    /** 读不到剩余空间（下载器离线等）按台节流：离线本身已由下载追踪报过，这里只说开始与恢复 */
    private final FaultThrottle readFailures = new FaultThrottle();

    /** 这台下载器的类型/版本不提供剩余空间：配置不改就一直成立，只说一次 */
    private final LogOnce unsupported = new LogOnce();

    public DownloaderSpaceService(IPtDownloaderPlusService downloaderService,
                                  DownloaderClientFactory clientFactory,
                                  DownloaderSpaceRegistry registry) {
        this.downloaderService = downloaderService;
        this.clientFactory = clientFactory;
        this.registry = registry;
    }

    /**
     * 一轮检查的结果。
     *
     * @param checked   读到了剩余空间的台数
     * @param low       当前低于告警线的台数
     * @param changed   本轮新进入低位或刚恢复的台数（心跳判据：只有它说明这一轮发生了事）
     */
    public record CheckOutcome(int checked, int low, int changed) {
    }

    public CheckOutcome checkAll() {
        List<PtDownloaderPlus> enabled = downloaderService.list(
                new LambdaQueryWrapper<PtDownloaderPlus>().eq(PtDownloaderPlus::getEnabled, "1"));
        Set<Integer> alive = new HashSet<>();
        int checked = 0;
        int low = 0;
        int changed = 0;
        for (PtDownloaderPlus d : enabled) {
            alive.add(d.getId());
            Long free = read(d);
            if (free == null) {
                continue;
            }
            checked++;
            Transition t = evaluate(d, free, new Date());
            if (registry.low(d.getId()) != null) {
                low++;
            }
            if (t == Transition.ENTERED || t == Transition.RECOVERED) {
                changed++;
            }
        }
        forgetGone(alive);
        return new CheckOutcome(checked, low, changed);
    }

    /** 读一台；读不到返回 null。成功的读数写进登记表 */
    Long read(PtDownloaderPlus d) {
        String key = String.valueOf(d.getId());
        try {
            Long free = clientFactory.get(d).freeSpace(d);
            if (free == null) {
                if (unsupported.firstTime(key)) {
                    log.info("下载器[{}]（{}）读不到剩余空间，不做空间告警（Transmission 需 2.80 以上，且保存路径要在它那台机器上存在）",
                            d.getName(), d.getType());
                }
                return null;
            }
            unsupported.forget(key);
            if (readFailures.onSuccess(key)) {
                log.info("下载器[{}] 剩余空间恢复可读：{}", d.getName(), formatSize(free));
            }
            registry.record(d.getId(), free);
            return free;
        } catch (Exception e) {
            FaultThrottle.Decision decision = readFailures.onFailure(key);
            if (decision.shouldReport()) {
                log.warn("下载器[{}] 读取剩余空间失败（连续 {} 次）：{}", d.getName(),
                        decision.consecutiveFailures(), e.getMessage());
            }
            return null;
        }
    }

    enum Transition { NONE, ENTERED, STILL_LOW, RECOVERED }

    /** 按告警线判一次并在三个时刻发通知。纯判定 + 通知，单测直接调 */
    Transition evaluate(PtDownloaderPlus d, long free, Date now) {
        Long warn = d.freeSpaceWarnBytes();
        DownloaderSpaceRegistry.LowState state = registry.low(d.getId());
        boolean isLow = warn != null && free < warn;
        if (isLow && state == null) {
            registry.markLow(d.getId(), new DownloaderSpaceRegistry.LowState(now, now));
            log.warn("下载器[{}] 剩余空间 {}，低于告警线 {}", d.getName(), formatSize(free), formatSize(warn));
            notify("💾 下载器「" + StringUtils.escapeHtml(d.getName()) + "」剩余空间 " + formatSize(free)
                    + "，低于告警线 " + formatSize(warn)
                    + "\n磁盘写满后新任务会卡住或失败。可清理已做够时长的种子，或在下载器设置里开启「按空间删种」");
            return Transition.ENTERED;
        }
        if (isLow) {
            if (now.getTime() - state.lastNotified().getTime() >= REMIND_EVERY.toMillis()) {
                registry.markLow(d.getId(), new DownloaderSpaceRegistry.LowState(state.since(), now));
                long hours = (now.getTime() - state.since().getTime()) / 3_600_000L;
                notify("💾 下载器「" + StringUtils.escapeHtml(d.getName()) + "」剩余空间仍只有 " + formatSize(free)
                        + "（告警线 " + formatSize(warn) + "），已持续约 " + hours + " 小时");
            }
            return Transition.STILL_LOW;
        }
        // 不低了：可能是空间腾出来了，也可能是告警线被调低 / 清空了，两种都算恢复
        if (registry.clearLow(d.getId())) {
            log.info("下载器[{}] 剩余空间已回到 {}", d.getName(), formatSize(free));
            notify("✅ 下载器「" + StringUtils.escapeHtml(d.getName()) + "」剩余空间已回到 " + formatSize(free)
                    + (warn == null ? "（已不再设告警线）" : "（告警线 " + formatSize(warn) + "）"));
            return Transition.RECOVERED;
        }
        return Transition.NONE;
    }

    /** 停用或删除了的下载器从登记表里清掉，否则首页会一直挂着一台已经不存在的下载器 */
    private void forgetGone(Set<Integer> alive) {
        for (Integer id : registry.knownIds()) {
            if (!alive.contains(id)) {
                registry.forget(id);
            }
        }
    }

    private void notify(String msg) {
        try {
            TgHelper.sendMsg(NotificationType.GENERAL, msg);
        } catch (Exception e) {
            log.debug("剩余空间通知发送失败（不影响检查）：{}", e.getMessage());
        }
    }

    /** 0 字节也要写出来（{@code PtNotifyText#size} 对 0 返回 null，那是「整段不写」的语义，这里不适用） */
    public static String formatSize(Long bytes) {
        if (bytes == null) {
            return "未知";
        }
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 1 ? String.format(Locale.ROOT, "%.1f GB", gb)
                : String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024));
    }
}
