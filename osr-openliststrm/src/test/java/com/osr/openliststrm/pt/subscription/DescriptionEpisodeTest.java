package com.osr.openliststrm.pt.subscription;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DescriptionEpisode} 的判据守卫。
 * <p>
 * 这里的假阳性用例（总集数、剧情简介里的数字）比正例更要紧：解析不出集号只是退回现状
 * ——当整季包处理、由下载器文件列表事后对账；解析出<b>错的</b>集号则会让种子去认领一个
 * 它根本没有的集，下错内容且没有任何一层能发现。
 * </p>
 *
 * @author Jack
 */
class DescriptionEpisodeTest {

    /** 实测样本：HHanClub 的 Torznab 条目，标题里没有集号，集号只在 description 的元信息区 */
    private static final String REAL_SAMPLE =
            "九门 / 老九门2 / 老九门贰 | 第21集 | 4K 高码 杜比视界 | 类型: 剧情/奇幻/冒险 | "
                    + "导演: 柏杉 | 主演: 陈伟霆/陈瑶/曾舜晞/王茂蕾/王奕婷";

    @Test
    void 实测样本_解析出第21集() {
        DescriptionEpisode.Episodes episodes = DescriptionEpisode.parse(REAL_SAMPLE);

        assertEquals(21, episodes.start());
        assertEquals(21, episodes.end());
        assertFalse(episodes.isRange(), "单集不是区间，下游据此决定 episodeEnd 是否留 null");
    }

    @Test
    void 集数区间_解析出起止且判定为区间() {
        DescriptionEpisode.Episodes episodes = DescriptionEpisode.parse("某剧 | 第01-26集 | 1080p");

        assertEquals(1, episodes.start());
        assertEquals(26, episodes.end());
        assertTrue(episodes.isRange());
    }

    @Test
    void 区间优先于单集_不会只取到区间的起始集() {
        // 单集正则要求「集」紧跟数字，第01-26集 本就不该命中它；这条用例守住这个前提，
        // 万一将来放宽了单集正则，区间会被截成单集、后面 25 集永远补不到
        DescriptionEpisode.Episodes episodes = DescriptionEpisode.parse("第01-26集");

        assertTrue(episodes.isRange());
        assertEquals(26, episodes.end());
    }

    @Test
    void 总集数写法一律不当作集号() {
        // 这些说的是「这部剧一共/已经更新到多少集」。当成集号会让种子认领一个它没有的集
        assertNull(DescriptionEpisode.parse("某剧 | 更新至第30集 | 1080p"));
        assertNull(DescriptionEpisode.parse("某剧 | 至第30集 | 1080p"));
        assertNull(DescriptionEpisode.parse("某剧 | 共第30集 | 1080p"));
        assertNull(DescriptionEpisode.parse("某剧 | 全第30集 | 1080p"));
    }

    @Test
    void 四位数不当作集号_那几乎必然是年份() {
        assertNull(DescriptionEpisode.parse("某剧 | 第2019集 | 1080p"));
    }

    @Test
    void 第0集不存在_匹到即说明不是集号() {
        assertNull(DescriptionEpisode.parse("某剧 | 第0集 | 1080p"));
    }

    @Test
    void 集话期后面再跟汉字的_是自然语言不是集号() {
        // 「话」确实紧跟着 21，但它在这里是「说话」的话。站点模板里集号总是独立字段，
        // 后面跟的是空白或分隔符；跟着汉字的必然是句子。不挡住这种，description 后半段的
        // 剧情简介会源源不断地贡献假集号
        assertNull(DescriptionEpisode.parse("导演在第21话说的是另一件事"));
        assertNull(DescriptionEpisode.parse("本片第3集数据由某站提供"));
    }

    @Test
    void 紧跟收尾汉字的写法会漏掉_这是刻意选的方向() {
        // 漏掉只是退回「当整季包处理、由下载器文件列表事后对账」的既有行为；
        // 认错集则会让种子认领一个它没有的集，实打实下错内容。守住这个取舍，
        // 将来有人想放宽后置约束时先看到代价在哪一边
        assertNull(DescriptionEpisode.parse("某剧 | 第21集完 | 1080p"));
    }

    @Test
    void 没有集号信息时返回null_调用方维持整季包的既有行为() {
        assertNull(DescriptionEpisode.parse("九门 | 4K 高码 杜比视界 | 类型: 剧情/奇幻/冒险"));
    }

    @Test
    void 空描述返回null() {
        assertNull(DescriptionEpisode.parse(null));
        assertNull(DescriptionEpisode.parse(""));
        assertNull(DescriptionEpisode.parse("   "));
    }

    @Test
    void 话与期同样识别_不同站的用词不一样() {
        assertEquals(7, DescriptionEpisode.parse("某番 | 第7话 | 1080p").start());
        assertEquals(7, DescriptionEpisode.parse("某番 | 第7話 | 1080p").start());
        assertEquals(7, DescriptionEpisode.parse("某综艺 | 第7期 | 1080p").start());
    }

    @Test
    void 数字与单位之间允许空白() {
        assertEquals(21, DescriptionEpisode.parse("某剧 | 第 21 集 | 1080p").start());
    }
    // ---------- SxxExx 写法 ----------

    /** 实测样本：青蛙站的 Torznab 条目，标题标成整季，真实范围只在 description 里 */
    private static final String QINGWA_SAMPLE =
            "Re：从零开始的异世界生活 / Re:ゼロから始める異世界生活 / "
                    + "Re:Zero kara Hajimeru Isekai Seikatsu / Re:ZERO -Starting Life in Another World- "
                    + "| S01E51-E66 | 内封简繁字幕";

    @Test
    void 青蛙实测样本_解析出S01的E51到E66() {
        DescriptionEpisode.Episodes episodes = DescriptionEpisode.parse(QINGWA_SAMPLE);

        assertNotNull(episodes);
        assertEquals(1, episodes.season());
        assertEquals(51, episodes.start());
        assertEquals(66, episodes.end());
        assertTrue(episodes.isRange());
    }

    @Test
    void 区间的各种写法() {
        assertEquals(66, DescriptionEpisode.parse("x | S01E51-66 | y").end());
        assertEquals(66, DescriptionEpisode.parse("x | S01E51-S01E66 | y").end());
        assertEquals(66, DescriptionEpisode.parse("x | E51-E66 | y").end());
        assertEquals(66, DescriptionEpisode.parse("x | EP51-EP66 | y").end());
        assertEquals(66, DescriptionEpisode.parse("x | s01e51-e66 | y").end());
        assertEquals(66, DescriptionEpisode.parse("x | S01E51 - E66 | y").end());
    }

    @Test
    void 单集写法() {
        DescriptionEpisode.Episodes episodes = DescriptionEpisode.parse("某剧 | S01E21 | 1080p");

        assertNotNull(episodes);
        assertEquals(1, episodes.season());
        assertEquals(21, episodes.start());
        assertFalse(episodes.isRange());
    }

    @Test
    void 集号允许四位_长篇动画的绝对编号是常态() {
        assertEquals(1173, DescriptionEpisode.parse("海贼王 | S01E1173 | 2160p").start());
    }

    @Test
    void 季号缺失时season为null_中文写法同样为null() {
        assertNull(DescriptionEpisode.parse("某剧 | E21 | 1080p").season());
        assertNull(DescriptionEpisode.parse("某剧 | 第21集 | 1080p").season());
    }

    @Test
    void 区间结尾省略E前缀时不认四位数_否则年份会被当成结尾集号() {
        // E01-2021 只可能是「第 1 集」加一个年份，不是 1~2021 集
        DescriptionEpisode.Episodes episodes = DescriptionEpisode.parse("某剧 | E01-2021 | 1080p");

        assertNotNull(episodes);
        assertFalse(episodes.isRange(), "应降级成单集 E01 而不是区间 1-2021");
        assertEquals(1, episodes.start());
    }

    @Test
    void 编码串里的字母数字不会被当成集号() {
        assertNull(DescriptionEpisode.parse("某剧 | 2160p HEVC 10bit TrueHD7.1 Atmos | 内封字幕"),
                "HEVC 的 E、TrueHD7 的 D7 都不是词首 E+数字");
        assertNull(DescriptionEpisode.parse("某剧 | WEB-DL DDP5.1 x265-FROGE | 1080p"));
    }

    @Test
    void SxxExx优先于中文写法_两者冲突时信前者() {
        assertEquals(21, DescriptionEpisode.parse("某剧 | S01E21 | 更新至第30集").start());
    }

    @Test
    void 中文写法照旧可用_SxxExx缺席时不受影响() {
        assertEquals(21, DescriptionEpisode.parse(REAL_SAMPLE).start());
    }
}
