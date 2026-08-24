package com.osr.openliststrm.pt.autoadd.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubanRssParserTest {

    private String wrap(String items) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>豆瓣电影</title>
                %s
                  </channel>
                </rss>
                """.formatted(items);
    }

    private String item(String title, String link) {
        return """
                    <item>
                      <title>%s</title>
                      <link>%s</link>
                    </item>
                """.formatted(title, link);
    }

    @Test
    void parse_标准条目_标题与豆瓣id都取到() {
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("漫长的季节", "https://movie.douban.com/subject/35593344/")));

        assertEquals(1, items.size());
        assertEquals("漫长的季节", items.get(0).getTitle());
        assertEquals("35593344", items.get(0).getDoubanId());
        assertEquals("https://movie.douban.com/subject/35593344/", items.get(0).getSourceUrl());
        // 补全前 tmdbId 必须为空——AutoAddPopularService 正是靠它判断要不要走 PopularItemResolver
        assertNull(items.get(0).getTmdbId());
    }

    @Test
    void parse_尾部评分被剥掉() {
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("漫长的季节 9.4", "https://movie.douban.com/subject/35593344/")));

        assertEquals("漫长的季节", items.get(0).getTitle());
    }

    @Test
    void parse_尾部整数不当作评分剥掉() {
        // 《速度与激情 9》这类以序号结尾的片名在榜单里很常见。剥错了之后标题全等判定
        // 必然落空，而这个失败是完全静默的——只会表现为"这部片一直订不上"
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("速度与激情 9", "https://movie.douban.com/subject/26260853/")));

        assertEquals("速度与激情 9", items.get(0).getTitle());
    }

    @Test
    void parse_序号片名带评分时只剥评分() {
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("速度与激情 9 5.4", "https://movie.douban.com/subject/26260853/")));

        assertEquals("速度与激情 9", items.get(0).getTitle());
    }

    @Test
    void parse_尾部年份括号被剥掉并带出年份() {
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("奥本海默 (2023)", "https://movie.douban.com/subject/35593344/")));

        assertEquals("奥本海默", items.get(0).getTitle());
        assertEquals("2023", items.get(0).getYear());
    }

    @Test
    void parse_全角括号的年份同样被剥掉() {
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("奥本海默（2023）", "https://movie.douban.com/subject/1/")));

        assertEquals("奥本海默", items.get(0).getTitle());
        assertEquals("2023", items.get(0).getYear());
    }

    @Test
    void parse_评分与年份同时存在时都被剥掉() {
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("奥本海默 (2023) 8.9", "https://movie.douban.com/subject/1/")));

        assertEquals("奥本海默", items.get(0).getTitle());
        assertEquals("2023", items.get(0).getYear());
    }

    @Test
    void parse_片名本身就是年份时不剥() {
        // 剥空则不剥：《1917》《2012》这类片名剥掉之后什么都不剩，
        // 拿空串去搜 TMDb 只会白打一次请求
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("(1917)", "https://movie.douban.com/subject/1/")));

        assertEquals("(1917)", items.get(0).getTitle());
        assertNull(items.get(0).getYear());
    }

    @Test
    void parse_没有年份时year为空而不是0() {
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("漫长的季节", "https://movie.douban.com/subject/1/")));

        assertNull(items.get(0).getYear());
    }

    @Test
    void parse_链接不是豆瓣条目时doubanId为空但条目保留() {
        // 这个源顺带支持任意 RSS 地址，非豆瓣的条目拿不到 subject id 是正常的，
        // 不该因此把条目整个丢掉——标题才是补全流程真正需要的东西
        List<PopularItem> items = DoubanRssParser.parse(
                wrap(item("漫长的季节", "https://example.com/post/123")));

        assertEquals(1, items.size());
        assertNull(items.get(0).getDoubanId());
        assertEquals("漫长的季节", items.get(0).getTitle());
    }

    @Test
    void parse_缺链接的条目仍然保留() {
        List<PopularItem> items = DoubanRssParser.parse(wrap("""
                    <item>
                      <title>漫长的季节</title>
                    </item>
                """));

        assertEquals(1, items.size());
        assertNull(items.get(0).getSourceUrl());
    }

    @Test
    void parse_缺标题的条目被丢弃() {
        List<PopularItem> items = DoubanRssParser.parse(wrap("""
                    <item>
                      <link>https://movie.douban.com/subject/1/</link>
                    </item>
                """));

        assertTrue(items.isEmpty());
    }

    @Test
    void parse_多个条目保持顺序() {
        List<PopularItem> items = DoubanRssParser.parse(wrap(
                item("甲 8.0", "https://movie.douban.com/subject/1/")
                        + item("乙 7.0", "https://movie.douban.com/subject/2/")
                        + item("丙 6.0", "https://movie.douban.com/subject/3/")));

        assertEquals(List.of("甲", "乙", "丙"), items.stream().map(PopularItem::getTitle).toList());
    }

    @Test
    void parse_Atom格式同样能解析() {
        // RSSHub 同一个路由带不带 .atom 后缀给的是两种格式，用户很容易把带后缀的地址粘过来。
        // 只支持 RSS 的话表现是"一条都拉不到"而地址看着完全正常
        String atom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>豆瓣电影</title>
                  <entry>
                    <title>漫长的季节 9.4</title>
                    <link href="https://movie.douban.com/subject/35593344/"/>
                  </entry>
                </feed>
                """;

        List<PopularItem> items = DoubanRssParser.parse(atom);

        assertEquals(1, items.size());
        assertEquals("漫长的季节", items.get(0).getTitle());
        assertEquals("35593344", items.get(0).getDoubanId());
    }

    @Test
    void parse_空输入返回空列表而不是抛异常() {
        assertTrue(DoubanRssParser.parse(null).isEmpty());
        assertTrue(DoubanRssParser.parse("").isEmpty());
        assertTrue(DoubanRssParser.parse("   ").isEmpty());
    }

    @Test
    void parse_不认识的根元素返回空列表() {
        assertTrue(DoubanRssParser.parse("<html><body>502 Bad Gateway</body></html>").isEmpty());
    }

    @Test
    void parse_非法XML抛异常() {
        assertThrows(IllegalArgumentException.class, () -> DoubanRssParser.parse("<rss><channel>"));
    }
}
