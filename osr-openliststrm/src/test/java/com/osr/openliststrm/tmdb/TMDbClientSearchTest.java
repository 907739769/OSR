package com.osr.openliststrm.tmdb;

import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    // ---------- 门槛的最后一道证据：TMDb 的英文规范名（language=en-US） ----------

    /**
     * 真实漏网案例：{@code Mushoku.Tensei.Jobless.Reincarnation.S03E06.2026}。
     * 搜索结果按 zh-CN 本地化，name 是中文译名、original_name 是日文原名，发布组用的罗马字标题
     * 一个都不在里面；年份 2026 是第三季播出年，与首播 2021 差 5 年。两条证据全灭，
     * 只能靠 en-US 详情里的英文规范名把它捞回来。
     */
    private static final String ZH_JA_ONLY = "{\"results\":[{\"id\":94664,"
            + "\"name\":\"无职转生，到了异世界就拿出真本事\","
            + "\"original_name\":\"無職転生 ～異世界行ったら本気だす～\","
            + "\"first_air_date\":\"2021-01-11\",\"popularity\":90}]}";

    private MediaInfo animeInfo() {
        MediaInfo info = new MediaInfo("Mushoku.Tensei.Jobless.Reincarnation.S03E06.2026.1080p.NF.WEB-DL.x264-ADWeb.strm");
        info.setOriginalTitle("Mushoku Tensei Jobless Reincarnation");
        info.setYear("2026");
        info.setSeason("03");
        info.setEpisode("06");
        return info;
    }

    @Test
    void 门槛_中文名与日文原名都对不上时_靠英文规范名放行() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(ZH_JA_ONLY);
        when(api.getDetails(anyString(), eq("tv"), eq(94664), eq("en-US")))
                .thenReturn("{\"id\":94664,\"name\":\"Mushoku Tensei: Jobless Reincarnation\"}");

        MediaInfo info = animeInfo();

        // 采纳后标题仍取中文（getBestTitle 优先中文名），英文规范名只用于证明"是同一部作品"
        assertEquals("无职转生，到了异世界就拿出真本事", client.search("tv", info, api));
        assertEquals("94664", info.getTmdbId());
    }

    @Test
    void 门槛_前两条证据成立时_不发英文详情请求() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);
        when(api.search(anyString(), eq("tv"), eq("开始推理吧"), isNull())).thenReturn(TV_HIT);

        client.search("tv", tvInfo(), api);

        // 补查只走在"就要被拒"的路径上，正常命中的请求数不能变
        verify(api, never()).getDetails(anyString(), anyString(), anyInt(), eq("en-US"));
    }

    @Test
    void 门槛_英文规范名也对不上_仍然不采纳() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(IRRELEVANT);
        when(api.getDetails(anyString(), anyString(), anyInt(), eq("en-US")))
                .thenReturn("{\"id\":999,\"name\":\"Something Else Entirely\"}");

        MediaInfo info = tvInfo();

        assertNull(client.search("tv", info, api));
        assertNull(info.getTmdbId());
    }

    @Test
    void 门槛_英文详情请求失败_按证据不足处理而不是抛异常() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(ZH_JA_ONLY);
        // TMDbApiService 在 HTTP 失败时返回 null——mapper.readTree(null) 会抛 IllegalArgumentException，
        // 这类可有可无的补查绝不能把整次刮削带崩
        when(api.getDetails(anyString(), anyString(), anyInt(), eq("en-US"))).thenReturn(null);

        MediaInfo info = animeInfo();

        assertNull(client.search("tv", info, api));
        assertNull(info.getTmdbId());
    }

    @Test
    void 门槛_文件名没有年份的韩剧_靠英文规范名放行() throws Exception {
        // Flex.X.Cop.S02E02：文件名里压根没有年份，"年份接近"这条证据在构造上就不可能成立
        String korean = "{\"results\":[{\"id\":234789,\"name\":\"财阀×刑警\","
                + "\"original_name\":\"재벌 X 형사\",\"first_air_date\":\"2024-01-20\",\"popularity\":30}]}";
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(korean);
        when(api.getDetails(anyString(), eq("tv"), eq(234789), eq("en-US")))
                .thenReturn("{\"id\":234789,\"name\":\"Flex X Cop\"}");

        MediaInfo info = new MediaInfo("Flex.X.Cop.S02E02.1080p.DSNP.WEB-DL.AAC2.0.H.264-MWeb.strm");
        info.setOriginalTitle("Flex X Cop");
        info.setSeason("02");
        info.setEpisode("02");

        assertEquals("财阀×刑警", client.search("tv", info, api));
        assertEquals("234789", info.getTmdbId());
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

    // ---------- 排序分档：全等命中优先于包含命中 ----------

    /**
     * 真实事故：{@code [梦魇绝镇 第四季].From.2026.S04E10.2160p.AMZN.WEB-DL.H265.DDP5.1-UBWEB}
     * 被刮成《怪奇物语：1985故事集》，产出
     * {@code /电视剧/欧美剧/怪奇物语：1985故事集 (2026)/Season 04}。
     * <p>
     * 两处叠加：中文标题带着「第四季」搜不到（由 {@code TitleProcessor} 剥掉后缀修复），
     * 降级到英文名 {@code From} 后，打分让山寨的赢了——
     * {@code Stranger Things: Tales From 85} 原名里含 {@code from}（包含命中 +60）、
     * 首播 2026 与文件名年份完全一致（+40）、热度又高；真正的 {@code From (2022)}
     * 标题逐字相等（+100）却因为 2026 是<b>第四季播出年</b>、候选侧是首播年而被扣 15 分。
     * </p>
     */
    private static final String FROM_SEARCH = "{\"results\":["
            + "{\"id\":300,\"name\":\"怪奇物语：1985故事集\","
            + "\"original_name\":\"Stranger Things: Tales From 85\","
            + "\"first_air_date\":\"2026-01-01\",\"popularity\":400},"
            + "{\"id\":138502,\"name\":\"梦魇绝镇\",\"original_name\":\"From\","
            + "\"first_air_date\":\"2022-02-20\",\"popularity\":60}]}";

    private MediaInfo fromInfo() {
        MediaInfo info = new MediaInfo("[梦魇绝镇 第四季].From.2026.S04E10.2160p.AMZN.WEB-DL.H265.DDP5.1-UBWEB.strm");
        info.setOriginalTitle("From");
        info.setYear("2026");
        info.setSeason("04");
        info.setEpisode("10");
        return info;
    }

    @Test
    void 排序_全等命中排在包含命中之前_年份与热度都跨不过这一档() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(FROM_SEARCH);

        MediaInfo info = fromInfo();

        // "From" 逐字相等，哪怕年份被扣分、热度低于对方，也必须排在包含式命中之前
        assertEquals("梦魇绝镇", client.search("tv", info, api));
        assertEquals("138502", info.getTmdbId());
        // 年份改写成首播年，目录不会再长成「(2026)」
        assertEquals("2022", info.getYear());
    }

    @Test
    void 排序_全等候选被集号反证否决时_包含候选仍会被检验() throws Exception {
        // 分档只抬全等、不剔除其余候选：否则 Perfect World 那条修复路径会被一并剔掉
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(FROM_SEARCH);
        // 假设 From 只有 3 集（远装不下第 10 集），怪奇物语那部有 8 集
        when(api.getDetails(anyString(), eq("tv"), eq(138502)))
                .thenReturn("{\"id\":138502,\"number_of_episodes\":3}");
        when(api.getDetails(anyString(), eq("tv"), eq(300)))
                .thenReturn("{\"id\":300,\"number_of_episodes\":8}");

        MediaInfo info = fromInfo();

        assertEquals("怪奇物语：1985故事集", client.search("tv", info, api));
        assertEquals("300", info.getTmdbId());
    }

    // ---------- 集号反证 + 多候选：字面同名的另一部作品 ----------

    /**
     * 真实事故：{@code Perfect.World.S01E282.2021.2160p.TX.WEB-DL.H.265.AAC2.0-HHWEB} 被刮成
     * TMDb 上 2000 年那部 6 集英国喜剧，产出 {@code /电视剧/欧美剧/完美世界 (2000)/Season 01/完美世界 S01E282}。
     * <p>
     * 成因是打分上的结构性不对称：英国剧的 {@code original_name} 与解析标题逐字相等（+100），
     * 正确答案国产动画《完美世界》的 name/original_name 都是中文、一分标题分都拿不到，只有年份吻合的加分。
     * 冠军又恰好能过采纳门槛（标题全等），于是没有任何环节能拦下它。
     * </p>
     */
    private static final String PERFECT_WORLD = "{\"results\":["
            + "{\"id\":2000,\"name\":\"完美世界\",\"original_name\":\"Perfect World\","
            + "\"first_air_date\":\"2000-02-25\",\"popularity\":3},"
            + "{\"id\":124364,\"name\":\"完美世界\",\"original_name\":\"完美世界\","
            + "\"first_air_date\":\"2021-04-23\",\"popularity\":25}]}";

    private MediaInfo perfectWorldInfo() {
        MediaInfo info = new MediaInfo("Perfect.World.S01E282.2021.2160p.TX.WEB-DL.H.265.AAC2.0-HHWEB.strm");
        info.setOriginalTitle("Perfect World");
        info.setYear("2021");
        info.setSeason("01");
        info.setEpisode("282");
        return info;
    }

    @Test
    void 反证_字面同名的老剧装不下第282集_改采纳年份吻合的次席() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(PERFECT_WORLD);
        // 2000 年的英国喜剧总共 6 集
        when(api.getDetails(anyString(), eq("tv"), eq(2000)))
                .thenReturn("{\"id\":2000,\"number_of_episodes\":6,\"origin_country\":[\"GB\"]}");
        when(api.getDetails(anyString(), eq("tv"), eq(124364)))
                .thenReturn("{\"id\":124364,\"number_of_episodes\":290,\"origin_country\":[\"CN\"]}");

        MediaInfo info = perfectWorldInfo();

        assertEquals("完美世界", client.search("tv", info, api));
        assertEquals("124364", info.getTmdbId());
        // 年份被改写成正确条目的首播年，目录不会再长成「完美世界 (2000)」
        assertEquals("2021", info.getYear());
    }

    @Test
    void 反证_只否决矛盾的那个候选_不整批放弃() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(PERFECT_WORLD);
        when(api.getDetails(anyString(), eq("tv"), eq(2000)))
                .thenReturn("{\"id\":2000,\"number_of_episodes\":6}");
        when(api.getDetails(anyString(), eq("tv"), eq(124364)))
                .thenReturn("{\"id\":124364,\"number_of_episodes\":290}");

        client.search("tv", perfectWorldInfo(), api);

        // 冠军被否决后就地降级到次席，不必再换标题重搜（换了也没有别的标题可换）
        verify(api, never()).search(anyString(), anyString(), anyString(), eq("2021"));
    }

    @Test
    void 反证_集号略微超出总集数时不否决_绝对集号命名是常态() throws Exception {
        // 发布组按绝对集号命名（One Piece S01E1173），TMDb 按季编号且数据常年滞后一两集，
        // 这里必须放行，否则整部长篇动画都会因为"集号超出"而拒绝刮削
        String onePiece = "{\"results\":[{\"id\":37854,\"name\":\"海贼王\",\"original_name\":\"ONE PIECE\","
                + "\"first_air_date\":\"1999-10-20\",\"popularity\":180}]}";
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(onePiece);
        when(api.getDetails(anyString(), eq("tv"), eq(37854)))
                .thenReturn("{\"id\":37854,\"number_of_episodes\":1141}");

        MediaInfo info = new MediaInfo("One.Piece.S01E1173.2026.1080p.WEB-DL.strm");
        info.setOriginalTitle("One Piece");
        info.setYear("2026");
        info.setSeason("01");
        info.setEpisode("1173");

        assertEquals("海贼王", client.search("tv", info, api));
        assertEquals("37854", info.getTmdbId());
    }

    @Test
    void 反证_拿不到总集数时不做判断() throws Exception {
        // 详情请求失败/字段缺失时反证一律不成立——它只在证据确凿时否决，绝不因为"查不到"而拒绝刮削
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(PERFECT_WORLD);
        when(api.getDetails(anyString(), eq("tv"), anyInt())).thenReturn(null);

        MediaInfo info = perfectWorldInfo();
        client.search("tv", info, api);

        // 退化回改造前的行为：打分冠军（字面同名的英国剧）被采纳
        assertEquals("2000", info.getTmdbId());
    }

    @Test
    void 反证_电影不参与() throws Exception {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn(EMPTY);
        when(api.search(anyString(), eq("movie"), eq("Fight Club"), eq("1999"))).thenReturn(MOVIE_HIT);

        MediaInfo info = new MediaInfo("Fight.Club.1999.1080p.BluRay.x264-GROUP.mkv");
        info.setOriginalTitle("Fight Club");
        info.setYear("1999");

        assertEquals("搏击俱乐部", client.search("movie", info, api));
        // 电影没有集号这一维，不该为它多发详情请求（fetchDetails 那次是采纳后的，不算）
        verify(api, never()).getDetails(anyString(), eq("tv"), anyInt());
    }
}
