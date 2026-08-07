package com.osr.openliststrm.pt.subscription;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.dto.MatchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionMatcherTest {

    private final SubscriptionMatcher matcher = new SubscriptionMatcher();

    private PtSubscriptionPlus tvSub(int id, String title, int season) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setMediaType("TV");
        sub.setTitle(title);
        sub.setSeason(season);
        return sub;
    }

    private PtSubscriptionPlus tvSub(int id, String title, String originalTitle, int season) {
        PtSubscriptionPlus sub = tvSub(id, title, season);
        sub.setOriginalTitle(originalTitle);
        return sub;
    }

    private PtSubscriptionPlus movieSub(int id, String title, String year) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setMediaType("MOVIE");
        sub.setTitle(title);
        sub.setYear(year);
        sub.setSeason(0);
        return sub;
    }

    private TorrentInfo torrent(String parsedTitle, String year, Integer season, Integer episode) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle("raw-title");
        t.setParsedTitle(parsedTitle);
        t.setParsedYear(year);
        t.setParsedSeason(season);
        t.setParsedEpisode(episode);
        return t;
    }

    private TorrentInfo torrent(String parsedTitle, String parsedTitleEn, String year, Integer season, Integer episode) {
        TorrentInfo t = torrent(parsedTitle, year, season, episode);
        t.setParsedTitleEn(parsedTitleEn);
        return t;
    }

    private TorrentInfo torrentRange(String parsedTitle, Integer season, Integer episode, Integer episodeEnd) {
        TorrentInfo t = torrent(parsedTitle, null, season, episode);
        t.setParsedEpisodeEnd(episodeEnd);
        return t;
    }

    // ---------- 剧集 ----------

    @Test
    void 剧集_标题与季号都匹配_返回对应集() {
        MatchResult result = matcher.match(torrent("Some Show", null, 1, 5),
                List.of(tvSub(10, "Some Show", 1)));

        assertNotNull(result);
        assertEquals(10, result.getSubscription().getId());
        assertEquals(5, result.getEpisode());
    }

    @Test
    void 剧集_种子标题用点分隔_订阅标题用空格_仍能匹配() {
        MatchResult result = matcher.match(torrent("Some.Show", null, 1, 5),
                List.of(tvSub(10, "Some Show", 1)));

        assertNotNull(result);
        assertEquals(10, result.getSubscription().getId());
    }

    @Test
    void 剧集_大小写不同_仍能匹配() {
        MatchResult result = matcher.match(torrent("SOME show", null, 1, 5),
                List.of(tvSub(10, "Some Show", 1)));

        assertNotNull(result);
    }

    @Test
    void 剧集_标题是订阅标题的前缀_不匹配() {
        // The Office 不该吃掉 The Office US 的种子，反之亦然
        assertNull(matcher.match(torrent("The Office", null, 1, 5),
                List.of(tvSub(10, "The Office US", 1))));
        assertNull(matcher.match(torrent("The Office US", null, 1, 5),
                List.of(tvSub(10, "The Office", 1))));
    }

    @Test
    void 剧集_季号不同_不匹配() {
        assertNull(matcher.match(torrent("Some Show", null, 2, 5),
                List.of(tvSub(10, "Some Show", 1))));
    }

    @Test
    void 剧集_有季号无集号_识别为季包返回负一() {
        MatchResult result = matcher.match(torrent("Some Show", null, 1, null),
                List.of(tvSub(10, "Some Show", 1)));

        assertNotNull(result);
        assertEquals(-1, result.getEpisode());
    }

    @Test
    void 剧集_没有季号_不匹配() {
        assertNull(matcher.match(torrent("Some Show", null, null, 5),
                List.of(tvSub(10, "Some Show", 1))));
    }

    @Test
    void 剧集_中文标题_能匹配() {
        MatchResult result = matcher.match(torrent("大明王朝1566", null, 1, 12),
                List.of(tvSub(10, "大明王朝1566", 1)));

        assertNotNull(result);
        assertEquals(12, result.getEpisode());
    }

    @Test
    void 剧集_集数区间_返回起始集号与区间结尾() {
        MatchResult result = matcher.match(torrentRange("Some Show", 1, 1, 3),
                List.of(tvSub(10, "Some Show", 1)));

        assertNotNull(result);
        assertEquals(1, result.getEpisode());
        assertEquals(3, result.getEpisodeEnd());
    }

    @Test
    void 剧集_区间结尾等于起始_按单集处理() {
        MatchResult result = matcher.match(torrentRange("Some Show", 1, 5, 5),
                List.of(tvSub(10, "Some Show", 1)));

        assertNotNull(result);
        assertEquals(5, result.getEpisode());
        assertNull(result.getEpisodeEnd());
    }

    // ---------- 电影 ----------

    @Test
    void 电影_标题与年份都匹配_集号为0() {
        MatchResult result = matcher.match(torrent("Fight Club", "1999", null, null),
                List.of(movieSub(20, "Fight Club", "1999")));

        assertNotNull(result);
        assertEquals(20, result.getSubscription().getId());
        assertEquals(0, result.getEpisode());
    }

    @Test
    void 电影_年份不同_不匹配() {
        // 同名翻拍太常见，年份不符宁可漏也不能串台
        assertNull(matcher.match(torrent("Fight Club", "2020", null, null),
                List.of(movieSub(20, "Fight Club", "1999"))));
    }

    @Test
    void 电影_标题带标点_仍匹配() {
        // 归一化收口到 TitleNormalizer 之前，PT 侧只处理 . _ -，冒号/撇号一律不认：
        // 刮削侧能匹配、订阅匹配侧却漏搜，同一部作品两条链路给出相反结论
        assertNotNull(matcher.match(torrent("Mission Impossible Fallout", "2018", null, null),
                List.of(movieSub(20, "Mission: Impossible - Fallout", "2018"))));
        assertNotNull(matcher.match(torrent("WALL E", "2008", null, null),
                List.of(movieSub(21, "WALL·E", "2008"))));
    }

    @Test
    void 剧集_标题带全角标点_仍匹配() {
        assertNotNull(matcher.match(torrent("神探夏洛克 可恶的新娘", null, 1, 1),
                List.of(tvSub(10, "神探夏洛克：可恶的新娘", 1))));
    }

    @Test
    void 标点归一化不会让不同作品串台() {
        // 归一化把标点换成空格而不是删除，全等比较的保护边界仍然成立
        assertNull(matcher.match(torrent("The Office US", null, 1, 1),
                List.of(tvSub(10, "The Office", 1))));
    }

    @Test
    void 电影_年份差一年_仍匹配() {
        // 电影节首映 vs 正式公映、年末上映跨年，同一部电影在不同来源差一年是常态，
        // 严格相等会把这一类完全正确的候选整条淘汰
        assertNotNull(matcher.match(torrent("Fight Club", "2000", null, null),
                List.of(movieSub(20, "Fight Club", "1999"))));
        assertNotNull(matcher.match(torrent("Fight Club", "1998", null, null),
                List.of(movieSub(20, "Fight Club", "1999"))));
    }

    @Test
    void 电影_年份差两年_不匹配() {
        // 容差止于 1 年：再放宽下去同名翻拍串台的风险实打实增加，
        // 而"正好差两年"的同一部电影几乎不存在
        assertNull(matcher.match(torrent("Fight Club", "2001", null, null),
                List.of(movieSub(20, "Fight Club", "1999"))));
    }

    @Test
    void movieYearMatches_边界与异常输入() {
        assertTrue(matcher.movieYearMatches("1999", "1999"));
        assertTrue(matcher.movieYearMatches("1999", " 2000 "));
        assertFalse(matcher.movieYearMatches("1999", "2001"));
        // 任一侧缺年份一律不匹配——电影没有季集号可交叉验证，判不出来时不能放行
        assertFalse(matcher.movieYearMatches(null, "1999"));
        assertFalse(matcher.movieYearMatches("1999", null));
        assertFalse(matcher.movieYearMatches("  ", "1999"));
        // 解析不出数字时退回字符串相等的结论，不抛异常
        assertFalse(matcher.movieYearMatches("1999", "abcd"));
        assertTrue(matcher.movieYearMatches("abcd", "abcd"));
    }

    @Test
    void 电影_种子无年份_不匹配() {
        assertNull(matcher.match(torrent("Fight Club", null, null, null),
                List.of(movieSub(20, "Fight Club", "1999"))));
    }

    @Test
    void 电影_订阅无年份_不匹配() {
        assertNull(matcher.match(torrent("Fight Club", "1999", null, null),
                List.of(movieSub(20, "Fight Club", null))));
    }

    @Test
    void 电影_解析出季集信息_不匹配电影订阅() {
        // 带季集的一定是剧集，不该匹配到电影订阅
        assertNull(matcher.match(torrent("Fight Club", "1999", 1, 5),
                List.of(movieSub(20, "Fight Club", "1999"))));
    }

    // ---------- 通用 ----------

    @Test
    void 解析标题为空_不匹配() {
        assertNull(matcher.match(torrent(null, "1999", 1, 5), List.of(tvSub(10, "Some Show", 1))));
        assertNull(matcher.match(torrent("  ", "1999", 1, 5), List.of(tvSub(10, "Some Show", 1))));
    }

    @Test
    void 订阅列表为空_不匹配() {
        assertNull(matcher.match(torrent("Some Show", null, 1, 5), List.of()));
    }

    @Test
    void 多个订阅_只匹配到对的那个() {
        List<PtSubscriptionPlus> subs = List.of(
                tvSub(10, "Other Show", 1),
                tvSub(11, "Some Show", 2),
                tvSub(12, "Some Show", 1));

        MatchResult result = matcher.match(torrent("Some Show", null, 1, 5), subs);

        assertNotNull(result);
        assertEquals(12, result.getSubscription().getId());
    }

    // ---------- 中英双标题匹配 ----------

    @Test
    void 种子只有英文标题_订阅英文原名匹配_能匹配() {
        // 种子纯英文命名：parsedTitle 落的就是英文（TitleProcessor 中文优先字段拿到的仍是英文），
        // parsedTitleEn 也可能同步有值；订阅只在 originalTitle 存了英文原名
        MatchResult result = matcher.match(torrent("Breaking Bad", "Breaking Bad", null, 1, 5),
                List.of(tvSub(10, "绝命毒师", "Breaking Bad", 1)));

        assertNotNull(result);
        assertEquals(10, result.getSubscription().getId());
        assertEquals(5, result.getEpisode());
    }

    @Test
    void 种子中英混排_订阅只有中文标题_靠中文匹配() {
        MatchResult result = matcher.match(torrent("绝命毒师", "Breaking Bad", null, 1, 5),
                List.of(tvSub(10, "绝命毒师", 1)));

        assertNotNull(result);
        assertEquals(10, result.getSubscription().getId());
    }

    @Test
    void 种子英文_订阅中文标题加英文原名_靠英文匹配() {
        MatchResult result = matcher.match(torrent("Breaking Bad", null, null, 1, 5),
                List.of(tvSub(10, "绝命毒师", "Breaking Bad", 1)));

        assertNotNull(result);
        assertEquals(10, result.getSubscription().getId());
    }

    @Test
    void 双标题求交集_TheOffice仍不误匹配TheOfficeUS() {
        assertNull(matcher.match(torrent("The Office", "The Office", null, 1, 5),
                List.of(tvSub(10, "The Office US", "The Office US", 1))));
        assertNull(matcher.match(torrent("The Office US", "The Office US", null, 1, 5),
                List.of(tvSub(10, "The Office", "The Office", 1))));
    }
}
