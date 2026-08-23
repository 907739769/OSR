package com.osr.openliststrm.pt;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.subscription.SubscriptionMatcher;
import com.osr.openliststrm.pt.subscription.SubscriptionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 日志里怎么称呼一部剧、一集、一批集。<b>日志侧的 {@link PtNotifyText} 对应物。</b>
 *
 * <p>为什么不直接复用 {@code PtNotifyText}：那份是按 Telegram 的 HTML parse_mode 写的，
 * 所有动态内容都过了 {@code escapeHtml}，于是 {@code Tom & Jerry} 在日志里会变成
 * {@code Tom &amp; Jerry}——而 {@code &} 在片名和组名里相当常见。日志没有 parse_mode，
 * 不需要也不该转义。
 *
 * <p>为什么需要它：改造前 40 处订阅相关日志写的是 {@code 订阅[148]}，而库里有 191 条订阅，
 * <b>没有任何人能从 id 认出那是哪部剧</b>。最刺眼的一处在 {@code StuckEpisodeSweepService}——
 * 上一行刚把 {@code title} 取出来喂给通知（通知里是《闪耀的她》），日志里仍然只写
 * {@code 订阅[148]}。通知层早就解决了「怎么向人标识一集」这个问题，日志层一直没跟上。
 *
 * @author Jack
 */
public final class PtLogText {

    private PtLogText() {
    }

    /**
     * 「哪部作品的哪一集」，与 {@link PtNotifyText#subject} 同一套说法（不转义）。
     * <p>
     * 保留 id 是有意的：日志的读者经常要拿它去查库或拼接口地址，而剧名不能用来做这件事。
     * 排版成 {@code 《剧名》S01E05[#148]}——名字在前，id 退到方括号里。
     * </p>
     *
     * @param sub        订阅；为 null 时返回空串，调用方自己决定要不要留位
     * @param episode    集号；null、电影、以及 {@link SubscriptionMatcher#SEASON_PACK} 各有说法
     * @param episodeEnd 区间集的末集，仅当大于 {@code episode} 时才写成区间
     */
    public static String subject(PtSubscriptionPlus sub, Integer episode, Integer episodeEnd) {
        if (sub == null) {
            return "";
        }
        // 标题为空时写「《未命名》」而不是让 StringBuilder 把 null 拼成字面量「《null》」——
        // 后者看起来像代码 bug，会把读日志的人引到错误的方向上去
        String title = sub.getTitle() == null || sub.getTitle().isBlank() ? "未命名" : sub.getTitle();
        StringBuilder sb = new StringBuilder("《").append(title).append("》");
        if (!SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()) && episode != null) {
            sb.append(" S").append(pad(sub.getSeason()));
            if (episode == SubscriptionMatcher.SEASON_PACK) {
                sb.append(" 全季");
            } else if (episodeEnd != null && episodeEnd > episode) {
                sb.append('E').append(pad(episode)).append("-E").append(pad(episodeEnd));
            } else {
                sb.append('E').append(pad(episode));
            }
        }
        return sb.append("[#").append(sub.getId()).append(']').toString();
    }

    /** 只认作品、不带集号时的简写 */
    public static String subject(PtSubscriptionPlus sub) {
        return subject(sub, null, null);
    }

    /**
     * 订阅对象取不到时的兜底称呼。<b>不要用它代替 {@link #subject}</b>——它存在的意义只是
     * 让「连订阅都查不到了」这件事在日志里看得出来，而不是悄悄退回成一个纯 id。
     */
    public static String subject(Integer subId) {
        return "订阅[#" + subId + "]";
    }

    /**
     * 一批集号压成人读得下去的一行：连续的并成区间。
     * <p>
     * 季包一次能放出几十集，逐个顿号列出来是一屏 {@code 1、2、3、…、1181}，
     * 既看不出规律又把整行日志撑爆；压成 {@code 1-24} 之后一眼就知道是整季。
     * </p>
     */
    public static String episodes(Collection<PtSubscriptionEpisodePlus> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            return "";
        }
        return numbers(episodes.stream().map(PtSubscriptionEpisodePlus::getEpisode).toList());
    }

    /** 同 {@link #episodes}，直接给集号 */
    public static String numbers(Collection<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return "";
        }
        List<Integer> sorted = new ArrayList<>(numbers).stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i <= sorted.size(); i++) {
            Integer cur = i < sorted.size() ? sorted.get(i) : null;
            if (cur != null && cur == prev + 1) {
                prev = cur;
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('、');
            }
            // 只有 3 集起才压区间：「5-6」和「5、6」一样长，而后者不用读者反应一下
            if (prev - start >= 2) {
                sb.append(start).append('-').append(prev);
            } else if (prev > start) {
                sb.append(start).append('、').append(prev);
            } else {
                sb.append(start);
            }
            if (cur != null) {
                start = cur;
                prev = cur;
            }
        }
        return sb.toString();
    }

    private static String pad(Integer n) {
        int v = n == null ? 1 : n;
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
