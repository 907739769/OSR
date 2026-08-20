package com.osr.openliststrm.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NFO 里的作品标题必须取识别结果（{@code info.title}），TMDb 详情只作兜底。
 * <p>
 * 守的是这个真实事故：{@code Ted.Lasso.S04E03.2020...strm} 被正确重命名到
 * 「足球教练 (2020)/Season 04/足球教练 S04E03 - ...」，同目录的 tvshow.nfo 里却写着
 * {@code <title>Ted Lasso</title>}——TMDb 没有这部剧的 zh-CN 翻译，
 * {@code /tv/97546?language=zh-CN} 的 name 直接退回英文，而三个 builder 都是
 * 「details.name 优先、info.title 兜底」，把 {@code TMDbClient#getBestTitle} 好不容易从
 * alternative_titles 里取回的中文别名挤掉了。媒体库显示的是 nfo 里那个，
 * 于是用户看到中文目录配英文剧名。
 * </p>
 */
class NfoTitleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** TMDb 缺 zh-CN 翻译时的典型详情响应：name 与 original_name 都是英文 */
    private static final String TV_DETAILS_EN = "{\"name\":\"Ted Lasso\",\"original_name\":\"Ted Lasso\","
            + "\"first_air_date\":\"2020-08-14\",\"number_of_episodes\":34}";

    private static final String MOVIE_DETAILS_EN = "{\"title\":\"Fight Club\",\"original_title\":\"Fight Club\","
            + "\"release_date\":\"1999-10-15\"}";

    private MediaInfo tvInfo(String title, String details) throws Exception {
        MediaInfo info = new MediaInfo("Ted.Lasso.S04E03.2020.1080p.ATVP.WEB-DL.H.264.DDP5.1.Atmos-HHWEB.strm");
        info.setTitle(title);
        info.setYear("2020");
        info.setSeason("04");
        info.setEpisode("03");
        info.setTmdbId("97546");
        if (details != null) {
            info.getMetadata().put("details", (JsonNode) MAPPER.readTree(details));
        }
        return info;
    }

    @Test
    void 剧集_TMDb缺中文翻译时_取中文别名而不是详情里的英文名() throws Exception {
        String nfo = new TvShowNfoBuilder().buildNfo(tvInfo("足球教练", TV_DETAILS_EN));

        assertTrue(nfo.contains("<title>足球教练</title>"), nfo);
        assertTrue(nfo.contains("<showtitle>足球教练</showtitle>"), nfo);
        // originaltitle 走 original_name，本来就该是原语言标题，不参与中文化
        assertTrue(nfo.contains("<originaltitle>Ted Lasso</originaltitle>"), nfo);
    }

    @Test
    void 剧集_识别结果为空时_回退到详情里的名字() throws Exception {
        String nfo = new TvShowNfoBuilder().buildNfo(tvInfo(null, TV_DETAILS_EN));

        assertTrue(nfo.contains("<title>Ted Lasso</title>"), nfo);
    }

    @Test
    void 单集_showtitle与剧集NFO同源() throws Exception {
        String nfo = new EpisodeNfoBuilder().buildNfo(tvInfo("足球教练", TV_DETAILS_EN));

        assertTrue(nfo.contains("<showtitle>足球教练</showtitle>"), nfo);
    }

    @Test
    void 电影_同样优先取中文别名() throws Exception {
        MediaInfo info = new MediaInfo("Fight.Club.1999.1080p.BluRay.x264.strm");
        info.setTitle("搏击俱乐部");
        info.setYear("1999");
        info.setTmdbId("550");
        info.getMetadata().put("details", MAPPER.readTree(MOVIE_DETAILS_EN));

        String nfo = new MovieNfoBuilder().buildNfo(info);

        assertTrue(nfo.contains("<title>搏击俱乐部</title>"), nfo);
        assertTrue(nfo.contains("<sorttitle>搏击俱乐部</sorttitle>"), nfo);
        assertTrue(nfo.contains("<originaltitle>Fight Club</originaltitle>"), nfo);
    }
}
