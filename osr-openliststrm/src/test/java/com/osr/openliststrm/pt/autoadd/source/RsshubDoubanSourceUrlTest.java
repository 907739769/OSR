package com.osr.openliststrm.pt.autoadd.source;

import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 地址拼接是这个数据源最容易配错的一环，而配错的表现全都是"一条都拉不到"，
 * 从日志上分不出是地址错了还是榜单真的空了。
 */
class RsshubDoubanSourceUrlTest {

    private String resolve(String sourceUrl, String baseUrl) {
        HttpUrl url = RsshubDoubanSource.resolveUrl(sourceUrl, baseUrl, 1);
        return url == null ? null : url.toString();
    }

    @Test
    void 路由路径与base拼接() {
        assertEquals("http://10.0.0.2:1200/douban/movie/weekly/movie_real_time_hotest",
                resolve("/douban/movie/weekly/movie_real_time_hotest", "http://10.0.0.2:1200"));
    }

    @Test
    void base带尾斜杠不会拼出双斜杠() {
        assertEquals("http://10.0.0.2:1200/douban/movie/playing",
                resolve("/douban/movie/playing", "http://10.0.0.2:1200/"));
    }

    @Test
    void 路由路径不带前导斜杠也能拼() {
        assertEquals("http://10.0.0.2:1200/douban/movie/playing",
                resolve("douban/movie/playing", "http://10.0.0.2:1200"));
    }

    @Test
    void base挂在子路径下时路由接在它后面() {
        // RSSHub 反代到 /rsshub 前缀是常见部署方式
        assertEquals("https://feed.example.com/rsshub/douban/movie/playing",
                resolve("/douban/movie/playing", "https://feed.example.com/rsshub"));
    }

    @Test
    void base带访问码时query被保留() {
        // 自建实例普遍开访问控制，把 ?key=xxx 写在全局地址里是最省事的用法；
        // 拼接时丢掉 query 的表现是每次请求都 403，而地址看着完全正常
        assertEquals("http://10.0.0.2:1200/douban/movie/playing?key=secret",
                resolve("/douban/movie/playing", "http://10.0.0.2:1200?key=secret"));
    }

    @Test
    void 完整URL直接使用并忽略base() {
        // 这条让数据源顺带支持任意 RSS 地址，不限于配好的那个 RSSHub 实例
        assertEquals("https://other.example.com/feed.xml",
                resolve("https://other.example.com/feed.xml", "http://10.0.0.2:1200"));
    }

    @Test
    void 完整URL在base为空时同样可用() {
        assertEquals("https://other.example.com/feed.xml",
                resolve("https://other.example.com/feed.xml", null));
    }

    @Test
    void 协议大小写不敏感() {
        assertEquals("https://other.example.com/feed.xml",
                resolve("HTTPS://other.example.com/feed.xml", "http://10.0.0.2:1200"));
    }

    @Test
    void 规则没配地址返回null() {
        assertNull(resolve(null, "http://10.0.0.2:1200"));
        assertNull(resolve("   ", "http://10.0.0.2:1200"));
    }

    @Test
    void 配了路由路径但全局地址为空返回null() {
        // 不去猜一个官方地址：官方实例常年限流/不可用，猜出来的地址只会把
        // "没配置"这个明确故障变成"看起来配好了但一条都拉不到"
        assertNull(resolve("/douban/movie/playing", null));
        assertNull(resolve("/douban/movie/playing", "  "));
    }

    @Test
    void 全局地址不是合法URL返回null() {
        assertNull(resolve("/douban/movie/playing", "10.0.0.2:1200"));
    }
}
