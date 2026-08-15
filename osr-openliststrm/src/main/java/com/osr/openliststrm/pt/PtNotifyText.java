package com.osr.openliststrm.pt;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SubscriptionMatcher;
import com.osr.openliststrm.pt.subscription.SubscriptionService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PT 通知文案里反复出现的几个片段的统一写法。
 * <p>
 * 抽出来不是为了省几行，而是因为「同一件事在不同通知里说法不一」本身就是缺陷：
 * 命中通知说《三体》S01E05、完成通知只说种子标题、失败通知又只说种子标题，
 * 用户根本对不上这三条讲的是同一集。这里的方法就是那份统一说法。
 * </p>
 * <p>
 * <b>这里的产物都是要拼进 Telegram HTML parse_mode 文案的，因此凡是来自数据库/索引器的
 * 动态内容都已在本类内部过了 {@link StringUtils#escapeHtml}</b>，调用方直接拼接即可，
 * 不要再转义一次（转两次会让 {@code &} 变成 {@code &amp;amp;}）。纯数字、单位这类
 * 代码里写死的部分不需要转义。
 * </p>
 *
 * @author Jack
 */
public final class PtNotifyText {

    private PtNotifyText() {
    }

    /**
     * 「这条通知讲的是哪部作品的哪一集」——所有 PT 通知的第一行都该以它开头。
     * <p>
     * 电影没有季集，只给书名号标题；剧集给到 {@code 《三体》 S01E05}；区间包给
     * {@code S01E05-E08}；季包给 {@code S01 全季}。集号补零到两位是刻意的：
     * 用户在通知列表里扫的是列宽对齐的一竖排，{@code E5} 和 {@code E12} 混排很难扫。
     * </p>
     *
     * @param episode    集号，{@link SubscriptionMatcher#SEASON_PACK} 表示整季，null 表示说不出集号
     * @param episodeEnd 区间包的结束集号，null 或不大于 {@code episode} 时按单集处理
     */
    public static String subject(PtSubscriptionPlus sub, Integer episode, Integer episodeEnd) {
        if (sub == null) {
            return "";
        }
        String title = "《" + StringUtils.escapeHtml(sub.getTitle()) + "》";
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()) || episode == null) {
            return title;
        }
        String season = " S" + pad(sub.getSeason());
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return title + season + " 全季";
        }
        return (episodeEnd != null && episodeEnd > episode)
                ? title + season + "E" + pad(episode) + "-E" + pad(episodeEnd)
                : title + season + "E" + pad(episode);
    }

    /**
     * 体积。GB 以下用 MB，否则一集 400MB 的番会显示成 "0.39 GB"，看着像出错了。
     *
     * @return 体积未知（null 或非正数）时返回 null，由调用方决定整行都不写——
     *         通知里写一句"体积：未知"只是占地方，没有任何指导意义
     */
    public static String size(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return null;
        }
        double gb = bytes / (1024.0 * 1024 * 1024);
        // 固定 Locale.ROOT：默认 Locale 在部分地区用逗号做小数点，"4,20 GB" 读起来像两个数
        return gb >= 1 ? String.format(Locale.ROOT, "%.2f GB", gb)
                : String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024));
    }

    /**
     * 两个时刻之间的耗时，说成人能读的粗粒度。
     * <p>
     * 只精确到分钟：用户看这个数是为了判断"这次下得快不快"，秒级精度对这个判断没有帮助，
     * 反而让文案变长。不足一分钟统一说"不到 1 分钟"。
     * </p>
     *
     * @return 任一时刻缺失或时序颠倒（时钟回拨、历史数据）时返回 null，调用方整行不写
     */
    public static String elapsed(Date from, Date to) {
        if (from == null || to == null) {
            return null;
        }
        long millis = to.getTime() - from.getTime();
        if (millis < 0) {
            return null;
        }
        long minutes = millis / 60_000;
        if (minutes < 1) {
            return "不到 1 分钟";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分钟";
    }

    /**
     * 种子的画像行：分辨率 / 来源 / 体积 / 做种数 / 站点，外加免费与 H&amp;R 两个标记。
     * <p>
     * 命中这一刻用户唯一想确认的是「抓到的版本对不对」，而种子标题未必写全（不少站点的
     * 标题里没有分辨率），原文案给的却是「已推送至下载器：qBittorrent」——只有一个下载器
     * 时那行是纯噪音。除站点名外全部来自已经解析好的 {@link TorrentInfo}，不额外查任何东西。
     * </p>
     * <p>
     * 每个片段各自缺失时就整段不写，不写「未知」：通知里的「分辨率：未知」不会帮用户
     * 做任何判断，只是把真正有用的几段挤下去。免费与 H&amp;R 排在最后单独给标记——它们直接
     * 决定用户要不要现在去管这个种子（下载量算不算、要保种多久），混在中间会被扫过去。
     * </p>
     *
     * @param indexerName 站点名，null（索引器已被删除）时整段省略，而不是显示一个悬空的 id
     * @return 以换行开头的一整行；一个片段都凑不出时返回空串，调用方直接拼接即可
     */
    public static String torrentProfile(TorrentInfo torrent, String indexerName) {
        if (torrent == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(torrent.getParsedResolution())) {
            parts.add(StringUtils.escapeHtml(torrent.getParsedResolution()));
        }
        if (StringUtils.isNotBlank(torrent.getParsedSource())) {
            parts.add(StringUtils.escapeHtml(torrent.getParsedSource()));
        }
        String size = size(torrent.getSize());
        if (size != null) {
            parts.add(size);
        }
        parts.add(torrent.getSeeders() + " 做种");
        if (StringUtils.isNotBlank(indexerName)) {
            parts.add(StringUtils.escapeHtml(indexerName));
        }
        if (torrent.getDownloadVolumeFactor() == 0) {
            parts.add("🆓 免费");
        }
        if (torrent.isHitAndRun()) {
            parts.add("🌱 H&amp;R");
        }
        return parts.isEmpty() ? "" : "\n" + String.join(" · ", parts);
    }

    /** 季号补零到两位，与集号保持同一种写法 */
    private static String pad(Integer number) {
        int value = number == null ? 1 : number;
        return value >= 0 && value < 10 ? "0" + value : String.valueOf(value);
    }
}
