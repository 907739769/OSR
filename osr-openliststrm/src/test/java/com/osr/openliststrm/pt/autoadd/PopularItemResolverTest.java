package com.osr.openliststrm.pt.autoadd;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.pt.autoadd.source.PopularItem;
import com.osr.openliststrm.tmdb.TMDbApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 这个类是整个豆瓣数据源里唯一会造成实际损失的地方：误匹配的后果不是"没订上"，
 * 而是订错剧并真的开始下载，且没有任何一层能发现。因此这里的用例大半是<b>反面</b>用例，
 * 钉的是"不该采纳的一个都别采纳"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PopularItemResolverTest {

    @Mock
    private TMDbApiService tmDbApiService;

    @Mock
    private OpenlistConfig openlistConfig;

    @InjectMocks
    private PopularItemResolver resolver;

    @BeforeEach
    void setUp() {
        when(openlistConfig.getTmdbApiKey()).thenReturn("key");
    }

    /** 造一条 TMDb search 的剧集响应 */
    private String tvResults(String... entries) {
        return "{\"results\":[" + String.join(",", entries) + "]}";
    }

    private String tv(int id, String name, String originalName, String firstAirDate) {
        return """
                {"id":%d,"name":"%s","original_name":"%s","first_air_date":"%s",
                 "vote_average":8.1,"vote_count":420,"genre_ids":[18,80],"poster_path":"/p.jpg"}
                """.formatted(id, name, originalName, firstAirDate);
    }

    private PopularItem douban(String title, String year) {
        PopularItem item = new PopularItem();
        item.setTitle(title);
        item.setYear(year);
        item.setDoubanId("35593344");
        item.setSourceUrl("https://movie.douban.com/subject/35593344/");
        return item;
    }

    @Test
    void resolve_标题全等_采纳并补全tmdbId() {
        when(tmDbApiService.search(eq("key"), eq("tv"), eq("漫长的季节"), isNull()))
                .thenReturn(tvResults(tv(203833, "漫长的季节", "漫长的季节", "2023-04-22")));

        PopularItem item = douban("漫长的季节", null);
        assertNull(resolver.resolve(item, "TV"));

        assertEquals("203833", item.getTmdbId());
        assertEquals("2023", item.getYear());
    }

    @Test
    void resolve_采纳后过滤所需字段一并补上() {
        // 规则上的"排除类型/最低评分/最低评分人数"三个过滤器全部依赖这几个字段。
        // 不补的话它们对豆瓣源恒为 null，按"不达标"处理会一条都放不过
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(1, "漫长的季节", "漫长的季节", "2023-04-22")));

        PopularItem item = douban("漫长的季节", null);
        resolver.resolve(item, "TV");

        assertEquals(8.1, item.getVoteAverage());
        assertEquals(420, item.getVoteCount());
        assertEquals(List.of(18, 80), item.getGenreIds());
    }

    @Test
    void resolve_采纳后保留来源侧标识() {
        // 匹配对不对只能靠日志回查，doubanId/sourceUrl 被覆盖掉就断了这条线索
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(1, "漫长的季节", "漫长的季节", "2023-04-22")));

        PopularItem item = douban("漫长的季节", null);
        resolver.resolve(item, "TV");

        assertEquals("35593344", item.getDoubanId());
        assertEquals("https://movie.douban.com/subject/35593344/", item.getSourceUrl());
    }

    @Test
    void resolve_标题只是包含关系_拒绝() {
        // 判据是全等而不是包含。放宽到包含的话，《三体》会匹配上《三体动画版》，
        // 而两者是不同的作品、不同的资源
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(1, "三体动画版", "三体动画版", "2022-12-10")));

        PopularItem item = douban("三体", null);
        assertNotNull(resolver.resolve(item, "TV"));
        assertNull(item.getTmdbId());
    }

    @Test
    void resolve_标点差异不影响全等判定() {
        // 归一化把标点压成空格，《神探夏洛克：可恶的新娘》与"神探夏洛克 可恶的新娘"是同一部
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(7, "神探夏洛克 可恶的新娘", "Sherlock", "2016-01-01")));

        PopularItem item = douban("神探夏洛克：可恶的新娘", null);
        assertNull(resolver.resolve(item, "TV"));
        assertEquals("7", item.getTmdbId());
    }

    @Test
    void resolve_原始语言标题全等也算命中() {
        // 日番/韩剧的 name 是中文译名、original_name 是原文，两边都要比
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(2, "咒术回战", "呪術廻戦", "2020-10-03")));

        PopularItem item = douban("呪術廻戦", null);
        assertNull(resolver.resolve(item, "TV"));
        assertEquals("2", item.getTmdbId());
    }

    @Test
    void resolve_年份差1_采纳() {
        // 跨年播出的剧两边可能各记一年，差 1 是常态
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(3, "某剧", "某剧", "2024-01-05")));

        PopularItem item = douban("某剧", "2023");
        assertNull(resolver.resolve(item, "TV"));
        assertEquals("3", item.getTmdbId());
    }

    @Test
    void resolve_年份差2_拒绝() {
        // 同名重拍/重启剧靠年份区分，放到 2 就开始串台
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(4, "某剧", "某剧", "2025-01-05")));

        PopularItem item = douban("某剧", "2023");
        assertNotNull(resolver.resolve(item, "TV"));
        assertNull(item.getTmdbId());
    }

    @Test
    void resolve_来源有年份而候选没有_拒绝() {
        // 能拿到一半信息却对不上，比两边都没有更可疑
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(5, "某剧", "某剧", "")));

        PopularItem item = douban("某剧", "2023");
        assertNotNull(resolver.resolve(item, "TV"));
    }

    @Test
    void resolve_来源没有年份_不检验年份() {
        // RSSHub 的豆瓣路由多数不给年份，要求必须有年份等于把这个源废掉
        when(tmDbApiService.search(any(), any(), any(), any()))
                .thenReturn(tvResults(tv(6, "某剧", "某剧", "1999-01-05")));

        PopularItem item = douban("某剧", null);
        assertNull(resolver.resolve(item, "TV"));
        assertEquals("6", item.getTmdbId());
    }

    @Test
    void resolve_搜不到结果_返回原因而不是抛异常() {
        when(tmDbApiService.search(any(), any(), any(), any())).thenReturn("{\"results\":[]}");

        PopularItem item = douban("查无此片", null);
        assertNotNull(resolver.resolve(item, "TV"));
        assertNull(item.getTmdbId());
    }

    @Test
    void resolve_TMDb返回非法JSON_返回原因而不是抛异常() {
        when(tmDbApiService.search(any(), any(), any(), any())).thenReturn("<html>502</html>");

        assertNotNull(resolver.resolve(douban("某剧", null), "TV"));
    }

    @Test
    void resolve_ApiKey未配置_直接返回原因且不发请求() {
        when(openlistConfig.getTmdbApiKey()).thenReturn("");

        assertNotNull(resolver.resolve(douban("某剧", null), "TV"));
        verify(tmDbApiService, never()).search(any(), any(), any(), any());
    }

    @Test
    void resolve_中英混排标题_整串搜不到时用中文段再搜一次() {
        // 豆瓣条目标题存在「中文名 English Name」拼接的写法，整串拿去搜 TMDb 一条都对不上
        when(tmDbApiService.search(eq("key"), eq("tv"), eq("漫长的季节 The Long Season"), isNull()))
                .thenReturn("{\"results\":[]}");
        when(tmDbApiService.search(eq("key"), eq("tv"), eq("漫长的季节"), isNull()))
                .thenReturn(tvResults(tv(203833, "漫长的季节", "漫长的季节", "2023-04-22")));

        PopularItem item = douban("漫长的季节 The Long Season", null);
        assertNull(resolver.resolve(item, "TV"));
        assertEquals("203833", item.getTmdbId());
    }

    @Test
    void resolve_中英混排标题_中文段也落空时用英文段() {
        when(tmDbApiService.search(eq("key"), eq("tv"), eq("漫长的季节 The Long Season"), isNull()))
                .thenReturn("{\"results\":[]}");
        when(tmDbApiService.search(eq("key"), eq("tv"), eq("漫长的季节"), isNull()))
                .thenReturn("{\"results\":[]}");
        when(tmDbApiService.search(eq("key"), eq("tv"), eq("The Long Season"), isNull()))
                .thenReturn(tvResults(tv(203833, "The Long Season", "The Long Season", "2023-04-22")));

        PopularItem item = douban("漫长的季节 The Long Season", null);
        assertNull(resolver.resolve(item, "TV"));
        assertEquals("203833", item.getTmdbId());
    }

    @Test
    void resolve_整串命中时不再发分段请求() {
        // 早停是有意的：常见情况下每个候选只打一次 TMDb
        when(tmDbApiService.search(eq("key"), eq("tv"), eq("漫长的季节 The Long Season"), isNull()))
                .thenReturn(tvResults(tv(1, "漫长的季节 The Long Season", "x", "2023-04-22")));

        resolver.resolve(douban("漫长的季节 The Long Season", null), "TV");

        verify(tmDbApiService, never()).search(any(), any(), eq("漫长的季节"), any());
        verify(tmDbApiService, never()).search(any(), any(), eq("The Long Season"), any());
    }

    @Test
    void resolve_纯中文标题不做分段_只发一次请求() {
        when(tmDbApiService.search(any(), any(), any(), any())).thenReturn("{\"results\":[]}");

        resolver.resolve(douban("漫长的季节", null), "TV");

        verify(tmDbApiService).search(eq("key"), eq("tv"), eq("漫长的季节"), isNull());
        verify(tmDbApiService, never()).search(any(), any(), eq("漫长的季"), any());
    }

    @Test
    void resolve_正确答案排在第一个之后仍能采纳() {
        // TMDb 按 popularity 倒序，热门的同名作品常排在前面
        when(tmDbApiService.search(any(), any(), any(), any())).thenReturn(tvResults(
                tv(1, "某剧前传", "某剧前传", "2020-01-01"),
                tv(2, "某剧", "某剧", "2023-04-22")));

        PopularItem item = douban("某剧", null);
        assertNull(resolver.resolve(item, "TV"));
        assertEquals("2", item.getTmdbId());
    }

    @Test
    void resolve_电影按movie类型搜索() {
        when(tmDbApiService.search(eq("key"), eq("movie"), eq("奥本海默"), isNull()))
                .thenReturn("{\"results\":[{\"id\":872585,\"title\":\"奥本海默\","
                        + "\"original_title\":\"Oppenheimer\",\"release_date\":\"2023-07-19\"}]}");

        PopularItem item = douban("奥本海默", null);
        assertNull(resolver.resolve(item, "MOVIE"));
        assertEquals("872585", item.getTmdbId());
        assertEquals("MOVIE", item.getMediaType());
    }

    @Test
    void resolve_标题为空_直接返回原因() {
        PopularItem item = new PopularItem();
        assertNotNull(resolver.resolve(item, "TV"));
    }

    @Test
    void queryVariants_纯中文只产出一个查询词() {
        assertEquals(List.of("漫长的季节"), PopularItemResolver.queryVariants("漫长的季节"));
    }

    @Test
    void queryVariants_纯英文只产出一个查询词() {
        assertEquals(List.of("The Long Season"), PopularItemResolver.queryVariants("The Long Season"));
    }

    @Test
    void queryVariants_中英混排产出三个且整串在最前() {
        List<String> variants = PopularItemResolver.queryVariants("漫长的季节 The Long Season");

        assertEquals(3, variants.size());
        assertEquals("漫长的季节 The Long Season", variants.get(0));
        assertEquals(List.of("漫长的季节", "The Long Season"), variants.subList(1, 3));
    }
}
