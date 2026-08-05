package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotifyTarget;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 「卡死在途」集的清扫：下载记录早已完成、集却一直没能入库时，把集退回缺失重新参与搜索。
 * <p>
 * <b>为什么需要这一层。</b>集的状态机里，IN_FLIGHT → IN_LIBRARY 由 Emby 对账驱动，
 * IN_FLIGHT → MISSING 只由 {@link DownloadTrackService#track} 的失败分支驱动。而失败分支
 * 只看 PUSHED/DOWNLOADING 的活跃记录——记录一旦 COMPLETED 就再不经过它。于是「下载成功了，
 * 但这一集的文件根本不在那个种子里」这个组合无人收尾：Emby 永远查不到它，对账又只升不降
 * （见 {@code SubscriptionService#refresh}），补搜与 RSS 只认 MISSING 不会再搜它，
 * 集就永久停在在途，前端显示"在途"但实际什么都没在下。
 * </p>
 * <p>
 * 最常见的成因是季包过度占位（整季包实际只含部分集）。那一路已经由
 * {@code DownloadTrackService#reconcileClaims} 用下载器的真实文件列表精确修正，
 * 本类是它照不到的场景的兜底：文件名一个集号都解析不出来、包里那一集是 SP/OVA 命名对不上、
 * STRM 生成或刮削环节把文件丢了、Emby 就是没刮出这一集……成因不同，症状都是同一个。
 * </p>
 * <p>
 * <b>两条安全约束。</b>其一，没有启用中的媒体服务器时整体跳过——那种配置下
 * {@code queryLibrary} 恒返回空集，任何集都不可能被推进 IN_LIBRARY，清扫会把每一次
 * 正常完成的下载都退回缺失，变成无限重下。其二，退回时<b>累加 fail_count</b>：
 * 与补缺集失败共用同一个熔断计数，同一集反复被扫到会在阈值处转 BLOCKED 停止自动重试。
 * 没有这条，一个永远对不上账的集会无休止地重下重扫，把索引器配额烧干。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class StuckEpisodeSweepService {

    private static final String EP_MISSING = SubscriptionEpisodeState.MISSING.value();
    private static final String EP_IN_FLIGHT = SubscriptionEpisodeState.IN_FLIGHT.value();
    private static final String EP_BLOCKED = SubscriptionEpisodeState.BLOCKED.value();

    private final IPtSubscriptionEpisodePlusService episodeService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtMediaServerPlusService mediaServerService;

    /**
     * 下载记录完成多久之后仍未入库才判定为卡死。
     * <p>
     * 默认给到 12 小时是刻意的宽松：下载完成到 Emby 能查到中间还隔着 STRM 生成、
     * 媒体库扫描、刮削三步，慢一点是正常的。判早了会把正在入库路上的集退回缺失、
     * 触发一次多余的重下；判晚了只是让一个本来就永久卡死的集多显示几小时。
     * </p>
     */
    private final int stuckTimeoutHours;

    /** 与补缺集失败共用的熔断阈值：同一集连续失败达到该次数后转 BLOCKED，停止自动重试 */
    private final int maxConsecutiveFailures;

    public StuckEpisodeSweepService(IPtSubscriptionEpisodePlusService episodeService,
                                    IPtSubscriptionPlusService subscriptionService,
                                    IPtMediaServerPlusService mediaServerService,
                                    @Value("${pt.download.stuck-episode-timeout-hours:12}") int stuckTimeoutHours,
                                    @Value("${pt.download.max-consecutive-failures:3}") int maxConsecutiveFailures) {
        this.episodeService = episodeService;
        this.subscriptionService = subscriptionService;
        this.mediaServerService = mediaServerService;
        this.stuckTimeoutHours = stuckTimeoutHours;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    /**
     * 扫一轮并释放卡死的集。
     *
     * @return 实际退回缺失的集数
     */
    public int sweep() {
        if (mediaServerService.getActive() == null) {
            // 没有媒体服务器 = 没有对账依据，IN_FLIGHT 永远不会被推进 IN_LIBRARY。
            // 此时清扫等于把每一次成功的下载都判成卡死，必须整体跳过
            log.debug("未配置启用中的媒体服务器，跳过卡死在途集清扫");
            return 0;
        }
        List<PtSubscriptionEpisodePlus> stuck = episodeService.listStuckInFlight(stuckTimeoutHours);
        if (stuck.isEmpty()) {
            return 0;
        }
        Map<Integer, List<Integer>> releasedBySub = new LinkedHashMap<>();
        Map<Integer, List<Integer>> blockedBySub = new LinkedHashMap<>();
        for (PtSubscriptionEpisodePlus episode : stuck) {
            int fails = (episode.getFailCount() == null ? 0 : episode.getFailCount()) + 1;
            boolean cut = fails >= maxConsecutiveFailures;
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(cut ? EP_BLOCKED : EP_MISSING);
            set.setFailCount(fails);
            boolean changed = episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", episode.getId())
                    // 条件更新兜住并发：这一轮扫描与 DownloadTrackService 的追踪轮询可能重叠，
                    // 集若已被别的路径推进（比如对账刚好把它标成入库），这次更新不该生效
                    .eq("state", EP_IN_FLIGHT)
                    // 实体的 null 会被 MyBatis-Plus 跳过，download_id 必须显式置空，
                    // 否则退回缺失的集仍指着那条已完成的记录
                    .set("download_id", null));
            if (!changed) {
                continue;
            }
            (cut ? blockedBySub : releasedBySub)
                    .computeIfAbsent(episode.getSubId(), k -> new ArrayList<>())
                    .add(episode.getEpisode());
        }
        int released = count(releasedBySub) + count(blockedBySub);
        if (released > 0) {
            notifyReleased(releasedBySub, blockedBySub);
            log.info("卡死在途集清扫完成：共释放 {} 个集（下载完成超过 {} 小时仍未入库）", released, stuckTimeoutHours);
        }
        return released;
    }

    private int count(Map<Integer, List<Integer>> bySub) {
        return bySub.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 按订阅汇总后再发通知，而不是每集发一条：一个季包能一次性放出几十集，
     * 逐集发通知会把用户的消息列表冲爆。
     */
    private void notifyReleased(Map<Integer, List<Integer>> releasedBySub, Map<Integer, List<Integer>> blockedBySub) {
        for (Integer subId : union(releasedBySub, blockedBySub)) {
            PtSubscriptionPlus sub = subscriptionService.getById(subId);
            String title = sub == null ? ("订阅#" + subId) : sub.getTitle();
            StringBuilder msg = new StringBuilder("🧹 有集下载完成超过 ")
                    .append(stuckTimeoutHours)
                    .append(" 小时仍未入库：《")
                    .append(StringUtils.escapeHtml(title))
                    .append("》");
            List<Integer> released = releasedBySub.get(subId);
            if (released != null && !released.isEmpty()) {
                msg.append("\n第 ").append(join(released)).append(" 集已退回缺失，将继续自动搜索补齐");
            }
            List<Integer> blocked = blockedBySub.get(subId);
            if (blocked != null && !blocked.isEmpty()) {
                msg.append("\n🚫 第 ").append(join(blocked)).append(" 集连续失败达 ").append(maxConsecutiveFailures)
                        .append(" 次，已停止自动重试，需到下载记录管理页人工处理");
            }
            notifySafely(msg.toString(), sub);
        }
    }

    /** 保持订阅出现顺序，让通知顺序与扫描顺序一致 */
    private List<Integer> union(Map<Integer, List<Integer>> first, Map<Integer, List<Integer>> second) {
        List<Integer> ids = new ArrayList<>(first.keySet());
        for (Integer id : second.keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String join(List<Integer> episodes) {
        return episodes.stream().sorted().map(String::valueOf).collect(Collectors.joining("、"));
    }

    /** 发通知但绝不让通知失败影响主流程（单测环境下 SpringUtils.getBean 会抛异常，这里兜住） */
    private void notifySafely(String msg, PtSubscriptionPlus sub) {
        try {
            TgHelper.sendMsg(NotificationType.SUBSCRIPTION_HIT, msg,
                    NotifyTarget.owner(sub == null ? null : sub.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }
}
