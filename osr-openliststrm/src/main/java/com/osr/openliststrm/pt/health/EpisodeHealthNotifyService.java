package com.osr.openliststrm.pt.health;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyTarget;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「有集播出多日仍未入库」的定期提醒。
 * <p>
 * <b>只提醒 {@link EpisodeHealthBucket#OVERDUE_MISSING} 这一档。</b>另外三档各自已经有人管：
 * 在途逾期由 {@code StuckEpisodeSweepService} 发 {@code LIBRARY_STUCK}；熔断在转 BLOCKED
 * 的那一刻已经通知过；无播出日期那档连"逾期"都算不出来，拿它去打扰用户只会稀释信号。
 * 体检<b>页面</b>展示全部四档，通知只挑无人认领的那一档——这条边界一旦模糊，
 * 同一集会从两三个渠道各通知一次，用户很快就会把整类通知关掉。
 * </p>
 * <p>
 * <b>按收件人聚合成一条，而不是每条订阅发一条。</b>首次启用时积压的订阅可能有几十条，
 * 逐条发等于一次性推几十条消息，比不提醒还糟。这与
 * {@code StuckEpisodeSweepService#notifyReleased} 的「按订阅聚合逐集」是同一个方向的收敛，
 * 只是又高了一层；与被明令禁止的「本轮汇总」通知不同的是，这里<b>没有</b>对应的逐条通知
 * 可供重复——不发这条就一条都没有。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class EpisodeHealthNotifyService {

    /** 指纹列宽上限，与 {@code last_overdue_notify_sign} 的列定义一致 */
    private static final int SIGNATURE_MAX_LENGTH = 255;

    /** 一条消息里最多列几部剧，其余折叠成「等 N 部」。剩下的照样写日志，不会丢 */
    private static final int MAX_TITLES_PER_MESSAGE = 15;

    private final EpisodeHealthService healthService;
    private final IPtSubscriptionPlusService subscriptionService;

    /**
     * 缺集情况没变化时，隔多少天重提醒一次。
     * <p>
     * 只按"发过就不再发"去重是不行的：一部永远补不上的剧提醒一次之后就再无声息，
     * 而它恰恰是最该被记住的那一部。反过来天天提醒同一批集又必然被用户关掉整类通知。
     * 一周一次是这两者之间的取舍。
     * </p>
     */
    private final int repeatDays;

    /** 关掉这项提醒的开关。体检页面不受影响——页面是用户主动去看的，通知是主动打扰用户的 */
    private final boolean enabled;

    public EpisodeHealthNotifyService(EpisodeHealthService healthService,
                                      IPtSubscriptionPlusService subscriptionService,
                                      @Value("${pt.health.notify-enabled:true}") boolean enabled,
                                      @Value("${pt.health.notify-repeat-days:7}") int repeatDays) {
        this.healthService = healthService;
        this.subscriptionService = subscriptionService;
        this.enabled = enabled;
        this.repeatDays = Math.max(1, repeatDays);
    }

    /**
     * 扫一轮并按需提醒。
     *
     * @return 实际发出的消息条数（按收件人计）
     */
    public int notifyOverdue() {
        if (!enabled) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<SubscriptionHealth> scanned = healthService.scan();

        // 本轮仍有逾期缺集的订阅 -> 它的指纹。同时用作下面"哪些订阅已经不缺了"的判据
        Map<Integer, String> currentSigns = new LinkedHashMap<>();
        List<SubscriptionHealth> due = new ArrayList<>();
        for (SubscriptionHealth health : scanned) {
            List<Integer> overdue = health.episodesIn(EpisodeHealthBucket.OVERDUE_MISSING);
            if (overdue.isEmpty()) {
                continue;
            }
            PtSubscriptionPlus sub = health.subscription();
            String sign = signatureOf(overdue);
            currentSigns.put(sub.getId(), sign);
            if (shouldNotify(sub, sign, now)) {
                due.add(health);
            }
        }
        clearResolved(currentSigns.keySet());
        if (due.isEmpty()) {
            return 0;
        }

        // 按归属人分组：无归属（owner_user_id IS NULL）的订阅走 NotifyTarget.owner(null)，
        // 由路由回退到默认接收人——系统里的历史订阅全是这种，当成"丢弃"会让它们凭空消失。
        // 手写循环而不是 Collectors.groupingBy：后者在分类函数返回 null 时直接抛 NPE，
        // 而 null 归属人在这里是最常见的取值，不是异常情况
        Map<Long, List<SubscriptionHealth>> byOwner = new LinkedHashMap<>();
        for (SubscriptionHealth health : due) {
            byOwner.computeIfAbsent(health.subscription().getOwnerUserId(), k -> new ArrayList<>()).add(health);
        }

        int sent = 0;
        Date notifiedAt = new Date(now);
        for (Map.Entry<Long, List<SubscriptionHealth>> entry : byOwner.entrySet()) {
            List<SubscriptionHealth> group = entry.getValue();
            log.info("逾期缺集提醒：归属人[{}] 共 {} 部剧，{}",
                    entry.getKey() == null ? "默认" : entry.getKey(), group.size(),
                    group.stream().map(h -> h.subscription().getTitle()).toList());
            if (notifySafely(buildMessage(group), entry.getKey())) {
                sent++;
            }
            // 通知时间与指纹在发送后才落库。发送失败时不落，下一轮会重试——
            // 先落库再发送的话，一次网络抖动就能让这批缺集在指纹变化前再也不提醒
            for (SubscriptionHealth health : group) {
                Integer subId = health.subscription().getId();
                subscriptionService.updateOverdueNotifyState(subId, currentSigns.get(subId), notifiedAt);
            }
        }
        return sent;
    }

    /**
     * 指纹相同且未到重提醒周期时跳过。
     * <p>
     * {@code last_overdue_notify_time} 为 null（历史行、或刚被 {@link #clearResolved} 清过）
     * 一律按"该提醒"处理：宁可多发一条，也不能因为状态缺失而静默丢掉——用户根本发现不了
     * 一条没发出来的通知。
     * </p>
     */
    private boolean shouldNotify(PtSubscriptionPlus sub, String sign, long now) {
        if (!sign.equals(sub.getLastOverdueNotifySign())) {
            return true;
        }
        Date last = sub.getLastOverdueNotifyTime();
        return last == null || now - last.getTime() >= repeatDays * 86_400_000L;
    }

    /** 上次通知过、这次已经不缺了的订阅，清空状态，让它下次再缺时能立刻提醒 */
    private void clearResolved(Set<Integer> stillOverdue) {
        for (PtSubscriptionPlus sub : subscriptionService.listOverdueNotified()) {
            if (!stillOverdue.contains(sub.getId())) {
                subscriptionService.updateOverdueNotifyState(sub.getId(), null, null);
            }
        }
    }

    /**
     * 指纹 = 集数 + 排序去重的集号。
     * <p>
     * 带集数前缀是为了压低截断后的碰撞：长篇动画一季上百集，集号串轻易超过列宽，
     * 而两批不同的缺集常常共享一长串相同的前缀，只截集号的话它们会被判成"没变化"。
     * </p>
     */
    static String signatureOf(List<Integer> episodes) {
        String joined = episodes.stream().sorted().distinct()
                .map(String::valueOf).collect(Collectors.joining(","));
        String sign = episodes.size() + ":" + joined;
        return sign.length() > SIGNATURE_MAX_LENGTH ? sign.substring(0, SIGNATURE_MAX_LENGTH) : sign;
    }

    /** 文案按 Telegram 的 HTML parse_mode 写，其余渠道由各自的 toPlainText 还原 */
    String buildMessage(List<SubscriptionHealth> group) {
        StringBuilder msg = new StringBuilder("📺 有 ").append(group.size())
                .append(" 部剧播出超过 ").append(healthService.getOverdueDays())
                .append(" 天仍未匹配到资源");
        int shown = Math.min(group.size(), MAX_TITLES_PER_MESSAGE);
        for (int i = 0; i < shown; i++) {
            SubscriptionHealth health = group.get(i);
            PtSubscriptionPlus sub = health.subscription();
            List<Integer> overdue = health.episodesIn(EpisodeHealthBucket.OVERDUE_MISSING);
            msg.append("\n\n<b>").append(StringUtils.escapeHtml(StringUtils.defaultString(sub.getTitle(), "未命名")))
                    .append("</b>");
            // 季号只在剧集上有意义，电影恒为 0，写出来是干扰
            if (sub.getSeason() != null && sub.getSeason() > 0) {
                msg.append(" S").append(String.format("%02d", sub.getSeason()));
            }
            msg.append("\n第 ").append(join(overdue)).append(" 集");
            Integer days = maxOverdueDays(health, overdue);
            if (days != null) {
                msg.append("，已播出 ").append(days).append(" 天");
            }
            String diagnoses = diagnosisLabels(health, overdue);
            if (StringUtils.isNotBlank(diagnoses)) {
                msg.append("\n原因：").append(StringUtils.escapeHtml(diagnoses));
            }
        }
        if (group.size() > shown) {
            msg.append("\n\n……等共 ").append(group.size()).append(" 部（其余见「缺集体检」页）");
        }
        msg.append("\n\n到「缺集体检」页可查看逐集诊断，并一键开启自动补搜或立即补搜");
        return msg.toString();
    }

    /** 逾期天数取这批集里最大的那个——"最久的那一集缺了多少天"才是用户要的紧迫度 */
    private Integer maxOverdueDays(SubscriptionHealth health, List<Integer> overdue) {
        Set<Integer> wanted = Set.copyOf(overdue);
        return health.episodes().stream()
                .filter(e -> wanted.contains(e.episode()))
                .map(EpisodeHealthItem::overdueDays)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    /** 这批集的诊断标签，去重后按枚举声明顺序 */
    private String diagnosisLabels(SubscriptionHealth health, List<Integer> overdue) {
        Set<Integer> wanted = Set.copyOf(overdue);
        Set<String> codes = health.episodes().stream()
                .filter(e -> wanted.contains(e.episode()))
                .map(EpisodeHealthItem::diagnosis)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Arrays.stream(EpisodeHealthDiagnosis.values())
                .filter(d -> codes.contains(d.name()))
                .map(EpisodeHealthDiagnosis::getLabel)
                .collect(Collectors.joining("、"));
    }

    private String join(List<Integer> episodes) {
        return episodes.stream().sorted().map(String::valueOf).collect(Collectors.joining("、"));
    }

    /** 发通知但绝不让通知失败影响主流程（单测环境下 SpringUtils.getBean 会抛异常，这里兜住） */
    private boolean notifySafely(String msg, Long ownerUserId) {
        try {
            TgHelper.sendMsg(NotificationType.EPISODE_OVERDUE, msg, NotifyTarget.owner(ownerUserId));
            return true;
        } catch (Exception e) {
            log.debug("发送逾期缺集通知失败（不影响主流程）：{}", e.getMessage());
            return false;
        }
    }
}
