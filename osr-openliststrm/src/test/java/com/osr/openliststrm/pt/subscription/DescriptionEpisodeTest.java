package com.osr.openliststrm.pt.subscription;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
