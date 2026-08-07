package com.osr.openliststrm.tmdb;

import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TMDbClient#search} 的编排顺序：外层换标题、内层降级年份，且剧集不带年份搜。
 * <p>
 * 守的是这个真实事故：{@code 开始推理吧.The.Truth.S04E18.2026.2160p.WEB-DL.H265.AAC-ADWeb}
 * 被重命名成另一部剧——主标题带 {@code first_air_date_year=2026} 搜不到（该字段是剧集<b>首播</b>年，
 * 而 2026 是第 4 季的播出年），旧实现接着拿 englishTitle "The Truth" 继续带年份搜，
 * 撞上一部 2026 年首播的同名剧并直接采纳。
 * </p>
 */
class TMDbClientSearchTest {

    private static final String EMPTY = "{\"results\":[]}";

    /** 命中《开始推理吧》：name 是中文，getBestTitle 会直接返回它，不再产生额外请求 */
    private static final String TV_HIT = "{\"results\":[{\"id\":123,\"name\":\"开始推理吧\","
            + "\"first_air_date\":\"2023-01-01\",\"popularity\":10}]}";

    private static final String MOVIE_HIT = "{\"results\":[{\"id\":550,\"title\":\"搏击俱乐部\","
            + "\"release_date\":\"1999-10-15\",\"popularity\":30}]}";

    private final TMDbClient client = new TMDbClient("test-key");

    private MediaInfo tvInfo() {
        MediaInfo info = new MediaInfo("开始推理吧.The.Truth.S04E18.2026.2160p.WEB-DL.H265.AAC-ADWeb.strm");
        info.setOriginalTitle("开始推理吧");
        info.setEnglishTitle("The Truth");
        info.setYear("2026");
        info.setSeason("04");
        info.setEpisode("18");
        return info;
    }

    @Test
    void 剧集_不带年份搜索_主标题即命中() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);
        when(api.search(anyString(), eq("tv"), eq("开始推理吧"), isNull())).thenReturn(TV_HIT);

        MediaInfo info = tvInfo();

        assertEquals("开始推理吧", client.search("tv", info, api));
    }

    @Test
    void 剧集_从不带上年份参数() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);
        when(api.search(anyString(), eq("tv"), eq("开始推理吧"), isNull())).thenReturn(TV_HIT);

        client.search("tv", tvInfo(), api);

        // first_air_date_year 过滤的是剧集首播年，拿本季播出年去过滤对 S2+ 恒定落空
        verify(api, never()).search(anyString(), anyString(), anyString(), eq("2026"));
    }

    @Test
    void 剧集_主标题命中后_不再尝试英文标题() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);
        when(api.search(anyString(), eq("tv"), eq("开始推理吧"), isNull())).thenReturn(TV_HIT);

        client.search("tv", tvInfo(), api);

        // 事故的直接成因：轮到 "The Truth" 就会撞上一部 2026 年首播的同名剧
        verify(api, never()).search(anyString(), anyString(), eq("The Truth"), any());
    }

    @Test
    void 剧集_命中后年份被改写成剧集首播年() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);
        when(api.search(anyString(), eq("tv"), eq("开始推理吧"), isNull())).thenReturn(TV_HIT);

        MediaInfo info = tvInfo();
        client.search("tv", info, api);

        // 重命名产出的年份恒为首播年，媒体库不会因为某季种子写了别的年份而分裂成两个条目
        assertEquals("2023", info.getYear());
    }

    @Test
    void 电影_先带年份再同一标题降级为不带年份() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);
        when(api.search(anyString(), eq("movie"), eq("Fight Club"), isNull())).thenReturn(MOVIE_HIT);

        MediaInfo info = new MediaInfo("Fight.Club.1999.1080p.BluRay.x264-GROUP.mkv");
        info.setOriginalTitle("Fight Club");
        info.setYear("1999");

        assertEquals("搏击俱乐部", client.search("movie", info, api));

        // primary_release_year 与文件名里的上映年是同一个量，先带年份是有意义的；
        // 差一年（电影节首映 vs 正式上映）由同一标题的下一级兜住
        InOrder order = inOrder(api);
        order.verify(api).search(anyString(), eq("movie"), eq("Fight Club"), eq("1999"));
        order.verify(api).search(anyString(), eq("movie"), eq("Fight Club"), isNull());
    }

    @Test
    void 电影_带年份即命中时_不再发不带年份的请求() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);
        when(api.search(anyString(), eq("movie"), eq("Fight Club"), eq("1999"))).thenReturn(MOVIE_HIT);

        MediaInfo info = new MediaInfo("Fight.Club.1999.1080p.BluRay.x264-GROUP.mkv");
        info.setOriginalTitle("Fight Club");
        info.setYear("1999");

        assertEquals("搏击俱乐部", client.search("movie", info, api));

        verify(api, never()).search(anyString(), eq("movie"), eq("Fight Club"), isNull());
    }

    @Test
    void 全部候选全落空_返回null() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);

        assertNull(client.search("tv", tvInfo(), api));
    }

    // ---------- 采纳门槛：标题命中 或 年份差 ≤1，两者都不满足则不采纳 ----------

    /** 标题、年份都对不上的垃圾结果——旧实现会靠 TMDb 相关度排序把它当成冠军直接采纳 */
    private static final String IRRELEVANT = "{\"results\":[{\"id\":999,\"name\":\"完全无关的另一部剧\","
            + "\"original_name\":\"Something Else Entirely\",\"first_air_date\":\"2015-01-01\","
            + "\"popularity\":500}]}";

    @Test
    void 门槛_标题与年份都对不上_不采纳且不写入info() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(IRRELEVANT);

        MediaInfo info = tvInfo();

        assertNull(client.search("tv", info, api));
        // 被拒的候选绝不能写 info：写了会让 needsAI 误判成"已识别"，AI 兜底就不触发了
        assertNull(info.getTmdbId());
        assertEquals("2026", info.getYear());
    }

    @Test
    void 门槛_只有年份接近也放行() throws Exception {
        // 宽档门槛：A（标题命中）或 B（年份差≤1）满足其一即可
        String yearOnly = "{\"results\":[{\"id\":777,\"name\":\"名字对不上的剧\","
                + "\"original_name\":\"Totally Different\",\"first_air_date\":\"2026-03-01\",\"popularity\":5}]}";
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(yearOnly);

        MediaInfo info = tvInfo();
        client.search("tv", info, api);

        assertEquals("777", info.getTmdbId());
    }

    @Test
    void 门槛_只有标题命中也放行_年份差很远() throws Exception {
        String titleOnly = "{\"results\":[{\"id\":123,\"name\":\"开始推理吧\","
                + "\"first_air_date\":\"2023-01-01\",\"popularity\":10}]}";
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(titleOnly);

        MediaInfo info = tvInfo();

        // 解析年份 2026 vs 首播 2023 差 3 年，靠标题全等放行——正是本次事故要保住的路径
        assertEquals("开始推理吧", client.search("tv", info, api));
    }

    @Test
    void 门槛_英文标题也算命中_不再只认中文() throws Exception {
        // 旧实现的 +100 走 getOfficialChineseTitle，要求候选名含中文，英文剧永远拿不到标题信号
        String english = "{\"results\":[{\"id\":2316,\"name\":\"The Office\","
                + "\"original_name\":\"The Office\",\"first_air_date\":\"2005-03-24\",\"popularity\":80}]}";
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(english);

        MediaInfo info = new MediaInfo("The.Office.S03E05.2019.1080p.WEB-DL");
        info.setOriginalTitle("The Office");
        info.setYear("2019");   // 第 3 季的播出年，与首播 2005 差 14 年
        info.setSeason("03");

        assertEquals("The Office", client.search("tv", info, api));
    }

    @Test
    void 门槛_长包含短算命中() throws Exception {
        // 发布组命名与 TMDb 规范名有出入是常态：Marvel's Daredevil ⊃ Daredevil
        String contains = "{\"results\":[{\"id\":61889,\"name\":\"Daredevil\","
                + "\"original_name\":\"Daredevil\",\"first_air_date\":\"2015-04-10\",\"popularity\":60}]}";
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(contains);

        MediaInfo info = new MediaInfo("Marvels.Daredevil.S01E01.1080p.WEB-DL");
        info.setOriginalTitle("Marvels Daredevil");
        info.setSeason("01");

        assertEquals("Daredevil", client.search("tv", info, api));
    }

    @Test
    void 门槛_过短的拉丁标题不靠包含放行() throws Exception {
        // "up" 只有两个字母，任意长标题都可能包含它，不能算证据
        String shortTitle = "{\"results\":[{\"id\":1,\"name\":\"Uptown Funk Documentary\","
                + "\"original_name\":\"Uptown Funk Documentary\",\"first_air_date\":\"2015-01-01\","
                + "\"popularity\":50}]}";
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(shortTitle);

        MediaInfo info = new MediaInfo("Up.S01E01.1080p");
        info.setOriginalTitle("Up");
        info.setSeason("01");

        assertNull(client.search("tv", info, api));
    }

    @Test
    void 门槛_标点差异不影响标题命中() throws Exception {
        String punctuated = "{\"results\":[{\"id\":42,\"name\":\"神探夏洛克：可恶的新娘\","
                + "\"first_air_date\":\"2016-01-01\",\"popularity\":20}]}";
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(punctuated);

        MediaInfo info = new MediaInfo("神探夏洛克 可恶的新娘.S01E01.1080p");
        info.setOriginalTitle("神探夏洛克 可恶的新娘");
        info.setSeason("01");

        assertEquals("神探夏洛克：可恶的新娘", client.search("tv", info, api));
    }

    @Test
    void 门槛_拒绝后继续降级到下一个候选标题() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        // 主标题搜到的是垃圾（会被门槛拒），英文标题搜到的是对的
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(IRRELEVANT);
        when(api.search(anyString(), eq("tv"), eq("The Truth"), isNull())).thenReturn(
                "{\"results\":[{\"id\":55,\"name\":\"The Truth\",\"original_name\":\"The Truth\","
                        + "\"first_air_date\":\"2026-01-01\",\"popularity\":3}]}");

        MediaInfo info = tvInfo();

        assertEquals("The Truth", client.search("tv", info, api));
        verify(api).search(anyString(), eq("tv"), eq("开始推理吧"), isNull());
    }
}
