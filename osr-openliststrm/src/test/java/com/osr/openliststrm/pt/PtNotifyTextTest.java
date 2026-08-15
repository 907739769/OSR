package com.osr.openliststrm.pt;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SubscriptionMatcher;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 通知文案片段的统一写法。这里钉住的都是「用户会直接读到」的东西，
 * 改动前先想清楚新的写法是不是真的更好读。
 *
 * @author Jack
 */
class PtNotifyTextTest {

    private PtSubscriptionPlus tv(String title, Integer season) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setTitle(title);
        sub.setSeason(season);
        sub.setMediaType("TV");
        return sub;
    }

    private PtSubscriptionPlus movie(String title) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setTitle(title);
        sub.setMediaType("MOVIE");
        return sub;
    }

    // ---------- subject ----------

    @Test
    void 剧集单集_季集号补零到两位() {
        assertEquals("《三体》 S01E05", PtNotifyText.subject(tv("三体", 1), 5, null));
    }

    /** 两位以上的集号原样保留，不会被补成三位 */
    @Test
    void 剧集单集_两位集号不再补零() {
        assertEquals("《海贼王》 S23E13", PtNotifyText.subject(tv("海贼王", 23), 13, null));
    }

    @Test
    void 区间包_写成起止集号() {
        assertEquals("《三体》 S01E05-E08", PtNotifyText.subject(tv("三体", 1), 5, 8));
    }

    /** episodeEnd 不大于 episode 时按单集处理，避免出现 "E05-E05" 这种自相矛盾的写法 */
    @Test
    void 区间包_结束集号无效时退化成单集() {
        assertEquals("《三体》 S01E05", PtNotifyText.subject(tv("三体", 1), 5, 5));
    }

    @Test
    void 季包_写成整季() {
        assertEquals("《三体》 S01 全季",
                PtNotifyText.subject(tv("三体", 1), SubscriptionMatcher.SEASON_PACK, null));
    }

    /** 电影没有季集这一维，硬拼 S01E01 只会让用户困惑 */
    @Test
    void 电影_只给标题() {
        assertEquals("《沙丘》", PtNotifyText.subject(movie("沙丘"), 1, null));
    }

    /** 集号说不出来时（历史记录没写 episode）退化成只给标题，而不是拼出 "SnullEnull" */
    @Test
    void 集号缺失_退化成只给标题() {
        assertEquals("《三体》", PtNotifyText.subject(tv("三体", 1), null, null));
    }

    /** 订阅被删除后下载记录仍在，通知不能因此炸掉，也不该出现空的书名号 */
    @Test
    void 订阅为空_返回空串() {
        assertEquals("", PtNotifyText.subject(null, 5, null));
    }

    /**
     * 产物要拼进 Telegram 的 HTML parse_mode 文案，标题里的 &amp; 必须先转义；
     * 不解析 HTML 的渠道再由 {@code WeComNotifier#toPlainText} 还原回去。
     */
    @Test
    void 标题里的HTML保留字_已转义() {
        assertEquals("《Tom &amp; Jerry》 S01E01", PtNotifyText.subject(tv("Tom & Jerry", 1), 1, null));
    }

    // ---------- size ----------

    @Test
    void 体积_GB以上保留两位小数() {
        assertEquals("4.20 GB", PtNotifyText.size((long) (4.2 * 1024 * 1024 * 1024)));
    }

    /** 一集 400MB 的番剧若按 GB 显示会变成 "0.39 GB"，看着像出错了 */
    @Test
    void 体积_不足1GB时改用MB() {
        assertEquals("400 MB", PtNotifyText.size(400L * 1024 * 1024));
    }

    @Test
    void 体积_未知时返回null由调用方整行不写() {
        assertNull(PtNotifyText.size(null));
        assertNull(PtNotifyText.size(0L));
        assertNull(PtNotifyText.size(-1L));
    }

    // ---------- torrentProfile ----------

    private TorrentInfo torrent() {
        TorrentInfo t = new TorrentInfo();
        t.setParsedResolution("1080p");
        t.setParsedSource("WEBDL");
        t.setSize(5_000_000_000L);
        t.setSeeders(12);
        return t;
    }

    /**
     * 命中这一刻用户想确认的是「抓到的版本对不对」——分辨率、来源、体积、站点，
     * 而不是「已推送至下载器：qBittorrent」（只有一个下载器时那行是纯噪音）。
     */
    @Test
    void 画像行_给出分辨率来源体积做种数与站点() {
        assertEquals("\n1080p · WEBDL · 4.66 GB · 12 做种 · 馒头",
                PtNotifyText.torrentProfile(torrent(), "馒头"));
    }

    /** 索引器被删掉时少一段，而不是显示一个悬空的 id */
    @Test
    void 画像行_站点名缺失时整段省略() {
        assertEquals("\n1080p · WEBDL · 4.66 GB · 12 做种",
                PtNotifyText.torrentProfile(torrent(), null));
    }

    /**
     * 不少站点的标题里没有分辨率，解析不出来就整段不写——
     * 写一句"分辨率：未知"不帮用户做任何判断，只是把有用的几段挤下去。
     */
    @Test
    void 画像行_解析不出的字段整段不写() {
        TorrentInfo t = torrent();
        t.setParsedResolution(null);
        t.setParsedSource(null);
        t.setSize(0);
        assertEquals("\n12 做种", PtNotifyText.torrentProfile(t, null));
    }

    /** 免费与 H&amp;R 直接决定用户要不要现在去管这个种子，排在最后单独给标记 */
    @Test
    void 画像行_免费与HR单独给标记() {
        TorrentInfo t = torrent();
        t.setDownloadVolumeFactor(0);
        t.setHitAndRun(true);
        assertEquals("\n1080p · WEBDL · 4.66 GB · 12 做种 · 馒头 · 🆓 免费 · 🌱 H&amp;R",
                PtNotifyText.torrentProfile(t, "馒头"));
    }

    /** 站点名里的 &amp; 同样要转义——产物是拼进 TG 的 HTML parse_mode 文案的 */
    @Test
    void 画像行_站点名里的HTML保留字已转义() {
        assertEquals("\n1080p · WEBDL · 4.66 GB · 12 做种 · A&amp;B",
                PtNotifyText.torrentProfile(torrent(), "A&B"));
    }

    // ---------- elapsed ----------

    @Test
    void 耗时_不足一分钟说成不到1分钟() {
        assertEquals("不到 1 分钟", PtNotifyText.elapsed(new Date(0), new Date(30_000)));
    }

    @Test
    void 耗时_一小时以内只说分钟() {
        assertEquals("23 分钟", PtNotifyText.elapsed(new Date(0), new Date(23 * 60_000)));
    }

    @Test
    void 耗时_超过一小时说成小时加分钟() {
        assertEquals("1 小时 5 分钟", PtNotifyText.elapsed(new Date(0), new Date(65 * 60_000)));
    }

    @Test
    void 耗时_整小时不带零分钟() {
        assertEquals("2 小时", PtNotifyText.elapsed(new Date(0), new Date(120 * 60_000)));
    }

    /** 时钟回拨或历史数据错乱时返回 null，不写出 "用时 -3 分钟" 这种明显失真的话 */
    @Test
    void 耗时_时序颠倒或缺失时返回null() {
        assertNull(PtNotifyText.elapsed(new Date(60_000), new Date(0)));
        assertNull(PtNotifyText.elapsed(null, new Date(0)));
        assertNull(PtNotifyText.elapsed(new Date(0), null));
    }
}
