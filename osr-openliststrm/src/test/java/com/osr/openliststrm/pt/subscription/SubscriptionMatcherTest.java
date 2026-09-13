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

    // ---------- 剧集年份：同名剧串台防线 ----------
    // 标题全等 + 季号相等这两道判据对同名剧完全无效，年份是第三道。判据是单向的
    // （种子标的是本季播出年，只会晚于整部剧的首播年），见 SubscriptionMatcher#seriesYearPlausible

    /** 实景：《人生复本》(Dark Matter, 2024) 的 S02 订阅认领了《暗物质》(Dark Matter, 2016) 的 S02 季包 */
    @Test
    void 剧集_同名的另一部剧_种子年份远早于首播年_不匹配() {
        PtSubscriptionPlus sub = tvSub(10, "人生复本", "Dark Matter", 2);
        sub.setYear("2024");

        assertNull(matcher.match(torrent("Dark Matter", "2016", 2, null), List.of(sub)));
    }

    @Test
    void 剧集_第N季晚于首播年很多_照常匹配() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 9);
        sub.setYear("2005");

        MatchResult result = matcher.match(torrent("Some Show", "2023", 9, 3), List.of(sub));

        assertNotNull(result);
        assertEquals(3, result.getEpisode());
    }

    @Test
    void 剧集_种子年份早一年_在容差内仍匹配() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1);
        sub.setYear("2025");

        assertNotNull(matcher.match(torrent("Some Show", "2024", 1, 1), List.of(sub)));
    }

    @Test
    void 剧集_种子年份早两年_超出容差_不匹配() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1);
        sub.setYear("2025");

        assertNull(matcher.match(torrent("Some Show", "2023", 1, 1), List.of(sub)));
    }

    /** 与电影相反：剧集种子不标年份相当常见，缺失即淘汰会清掉一大批正确候选 */
    @Test
    void 剧集_种子无年份_照常匹配() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1);
        sub.setYear("2024");

        assertNotNull(matcher.match(torrent("Some Show", null, 1, 1), List.of(sub)));
    }

    @Test
    void 剧集_订阅无年份_照常匹配() {
        assertNotNull(matcher.match(torrent("Some Show", "2016", 1, 1),
                List.of(tvSub(10, "Some Show", 1))));
    }

    /** 年份这一维要排在绝对编号兜底之前：不是这部剧，就没有「按绝对号解释」可言 */
    @Test
    void 剧集_年份对不上时_不再走绝对编号兜底() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 23);
        sub.setYear("2024");

        assertNull(matcher.match(torrent("Some Show", "2016", 1, 1173), List.of(sub),
                java.util.Map.of(10, absoluteMap(10, 1173, 18))));
    }

    @Test
    void seriesYearPlausible_边界与异常输入() {
        assertTrue(matcher.seriesYearPlausible(null, "2016"));
        assertTrue(matcher.seriesYearPlausible("2024", null));
        assertTrue(matcher.seriesYearPlausible("  ", "2016"));
        assertTrue(matcher.seriesYearPlausible("2024", "   "));
        // 解析不出数字一律放行，交给季集号去判
        assertTrue(matcher.seriesYearPlausible("2024", "两千零一十六"));
        assertTrue(matcher.seriesYearPlausible("不是年份", "2016"));
        // 两侧带空白照常比较
        assertFalse(matcher.seriesYearPlausible(" 2024 ", " 2016 "));
        assertTrue(matcher.seriesYearPlausible("2024", "2024"));
        assertTrue(matcher.seriesYearPlausible("2024", "2023"));
        assertFalse(matcher.seriesYearPlausible("2024", "2022"));
    }

    /** 造一个「绝对号 → 本地集号」映射，用于上面那条绝对编号用例 */
    private AbsoluteEpisodeMap absoluteMap(int subId, int absolute, int local) {
        com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus ep =
                new com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus();
        ep.setSubId(subId);
        ep.setEpisode(local);
        ep.setTmdbEpisodeNumber(absolute);
        return AbsoluteEpisodeMap.from(List.of(ep));
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

    // ---------- description 别名兜底 ----------

    /** 青蛙站的真实条目：种子标题是罗马音，而订阅的三个标题里没有罗马音这一种 */
    private static final String RE_ZERO_DESCRIPTION = "Re：从零开始的异世界生活 / Re:ゼロから始める異世界生活 / "
            + "Re:Zero kara Hajimeru Isekai Seikatsu / Re:ZERO -Starting Life in Another World- "
            + "| S01E51-E66 | 内封简繁字幕";

    private PtSubscriptionPlus reZeroSub() {
        PtSubscriptionPlus sub = tvSub(10, "Re：从零开始的异世界生活", "Re:ゼロから始める異世界生活", 1);
        sub.setEnglishTitle("Re:ZERO -Starting Life in Another World-");
        return sub;
    }

    @Test
    void 罗马音种子_三个订阅标题全对不上_靠description别名匹配() {
        TorrentInfo t = torrent("Re Zero kara Hajimeru Isekai Seikatsu", null, 1, 5);
        t.setDescription(RE_ZERO_DESCRIPTION);

        MatchResult result = matcher.match(t, List.of(reZeroSub()));

        assertNotNull(result, "别名列表里的中文名与订阅标题归一化后相等，应当匹配");
        assertEquals(10, result.getSubscription().getId());
        assertEquals(5, result.getEpisode());
    }

    @Test
    void 没有description时行为与改造前完全一致() {
        assertNull(matcher.match(torrent("Re Zero kara Hajimeru Isekai Seikatsu", null, 1, 5),
                List.of(reZeroSub())));
    }

    @Test
    void 标题匹配优先于别名_不被排在前面的订阅抢走() {
        // 种子标题指向订阅 20，而 description 的别名列表指向排在前面的订阅 10。
        // 两轮匹配的顺序就是为这个场景存在的：合并成一轮求交集会让订阅 10 抢先命中
        TorrentInfo t = torrent("Breaking Bad", null, 1, 5);
        t.setDescription("绝命毒师 / Breaking Bad Spinoff | 第5集");

        MatchResult result = matcher.match(t,
                List.of(tvSub(10, "绝命毒师", 1), tvSub(20, "Breaking Bad", 1)));

        assertNotNull(result);
        assertEquals(20, result.getSubscription().getId());
    }

    @Test
    void 别名段是剧情简介时不参与匹配() {
        TorrentInfo t = torrent("Some Unparsed Release", null, 1, 5);
        t.setDescription("少年在异世界醒来，发现自己拥有死亡回归的能力");

        assertNull(matcher.match(t, List.of(tvSub(10, "少年在异世界醒来", 1))));
    }

    @Test
    void 拼音命名的电影_靠description别名匹配_尾部年份不许挡路() {
        // M-Team 的真实条目：Gei a ma de qing shu 2026 1080p WEB-DL H.265 AAC-lijiang-tv
        // 标题解析出的是罗马音拼音，订阅的三个标题里没有这一种，第一轮必然落空；
        // 第二轮的别名 "给阿嬷的情书 (2026)" 曾因归一化后多出一个 2026 而与订阅标题不相等
        TorrentInfo t = torrent("Gei a ma de qing shu", "2026", null, null);
        t.setDescription("给阿嬷的情书 (2026)");

        MatchResult result = matcher.match(t, List.of(movieSub(30, "给阿嬷的情书", "2026")));

        assertNotNull(result, "别名剥掉年份后与订阅标题归一化相等，应当匹配");
        assertEquals(30, result.getSubscription().getId());
        assertEquals(SubscriptionMatcher.MOVIE_EPISODE, result.getEpisode());
    }

    @Test
    void 别名命中后仍要过季集判定_季号对不上照样不匹配() {
        TorrentInfo t = torrent("Re Zero kara Hajimeru Isekai Seikatsu", null, 3, 5);
        t.setDescription(RE_ZERO_DESCRIPTION);

        assertNull(matcher.match(t, List.of(reZeroSub())), "别名只解决『是不是这部剧』，不放宽季号");
    }

    // ---------- 外部 ID ----------

    private TorrentInfo withIds(TorrentInfo t, String imdb, String tmdb, Integer... categories) {
        t.setImdbId(imdb);
        t.setTmdbId(tmdb);
        t.setCategories(List.of(categories));
        return t;
    }

    @Test
    void 外部ID_标题对不上但IMDb相同_照样认领且季集判定不放宽() {
        // 站点用了与 TMDb 不同的译名：标题这一维永远对不上，RSS 此前一条都订不到
        PtSubscriptionPlus sub = tvSub(10, "足球教练", 1);
        sub.setImdbId("tt10986410");
        TorrentInfo t = withIds(torrent("Ted Lasso", null, 1, 5), "tt10986410", null);

        MatchResult result = matcher.match(t, List.of(sub));

        assertNotNull(result);
        assertEquals(5, result.getEpisode());
        assertNull(matcher.match(withIds(torrent("Ted Lasso", null, 2, 5), "tt10986410", null), List.of(sub)),
                "ID 只回答『是不是这部剧』，季号对不上照样不匹配");
    }

    @Test
    void 外部ID_同名剧标题相同但ID不同_不串台_改由ID一致的订阅认领() {
        // 《人生复本》(2024) 与《暗物质》(2016) 英文名都是 Dark Matter、都有第 2 季
        PtSubscriptionPlus darkMatter2024 = tvSub(1, "人生复本", "Dark Matter", 2);
        darkMatter2024.setImdbId("tt13651628");
        PtSubscriptionPlus darkMatter2016 = tvSub(2, "暗物质", "Dark Matter", 2);
        darkMatter2016.setImdbId("tt4159076");
        TorrentInfo t = withIds(torrent("Dark Matter", null, 2, null), "tt4159076", null);

        MatchResult result = matcher.match(t, List.of(darkMatter2024, darkMatter2016));

        assertNotNull(result);
        assertEquals(2, result.getSubscription().getId());
        assertNull(matcher.match(t, List.of(darkMatter2024)), "ID 明确不一致时标题相同也不认领");
    }

    @Test
    void 外部ID_IMDb不同但TMDb相同_以相同为准() {
        // 发布者把剧集的 IMDb 链接填成了某一集的页面，只凭 IMDb 不同就否决会误伤
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1);
        sub.setImdbId("tt1111111");
        sub.setTmdbId("1399");
        TorrentInfo t = withIds(torrent("Some Show", null, 1, 5), "tt2222222", "1399", 5040);

        assertNotNull(matcher.match(t, List.of(sub)));
        assertEquals(SubscriptionMatcher.Identity.SAME, matcher.identityOf(t, sub));
    }

    @Test
    void 外部ID_TMDb分不清电影剧集时不参与比较() {
        // movie/1399 与 tv/1399 是两部毫不相干的作品
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1);
        sub.setTmdbId("1399");

        assertEquals(SubscriptionMatcher.Identity.UNKNOWN,
                matcher.identityOf(withIds(torrent("X", null, 1, 5), null, "1399"), sub));
        assertEquals(SubscriptionMatcher.Identity.UNKNOWN,
                matcher.identityOf(withIds(torrent("X", null, 1, 5), null, "1399", 2040), sub),
                "电影分类的 tmdbid 不能拿来和剧集订阅比");
        assertEquals(SubscriptionMatcher.Identity.SAME,
                matcher.identityOf(withIds(torrent("X", null, 1, 5), null, "1399", 5040), sub));
        assertEquals(SubscriptionMatcher.Identity.DIFFERENT,
                matcher.identityOf(withIds(torrent("X", null, 1, 5), null, "1400", 5040), sub));
    }

    @Test
    void 外部ID_订阅侧IMDb未带前导零也能对上() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1);
        sub.setImdbId("tt0944947");

        assertEquals(SubscriptionMatcher.Identity.SAME,
                matcher.identityOf(withIds(torrent("X", null, 1, 5), "tt0944947", null), sub));
    }
}
