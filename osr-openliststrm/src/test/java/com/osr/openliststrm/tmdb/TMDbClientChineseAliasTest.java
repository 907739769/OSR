package com.osr.openliststrm.tmdb;

import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TMDb 缺中文翻译时，标题从 {@code alternative_titles} 里按地区优先级取中文别名。
 * <p>
 * 早先只认 {@code iso_3166_1=CN}，中文名只登记在 TW/HK 的作品（日番、港片常见）会一路退回英文；
 * 而 PT 订阅侧认 CN/TW/HK/SG，同一部作品在订阅列表里是中文、在媒体库里是英文。
 * 两侧现在共用 {@link TmdbTitleRegions#CHINESE}。
 * </p>
 */
class TMDbClientChineseAliasTest {

    private static final String TV_HIT = "{\"results\":[{\"id\":97546,\"name\":\"Ted Lasso\","
            + "\"original_name\":\"Ted Lasso\",\"first_air_date\":\"2020-08-14\",\"popularity\":50}]}";

    private static final String TV_DETAILS = "{\"name\":\"Ted Lasso\",\"number_of_episodes\":34}";

    private final TMDbClient client = new TMDbClient("test-key");

    private MediaInfo tvInfo() {
        MediaInfo info = new MediaInfo("Ted.Lasso.S04E03.2020.1080p.ATVP.WEB-DL.H.264.DDP5.1.Atmos-HHWEB.strm");
        info.setOriginalTitle("Ted Lasso");
        info.setEnglishTitle("Ted Lasso");
        info.setYear("2020");
        info.setSeason("04");
        info.setEpisode("03");
        return info;
    }

    private TMDbApiService apiWithAliases(String aliasesJson) {
        TMDbApiService api = mock(TMDbApiService.class);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn("{\"results\":[]}");
        when(api.search(anyString(), eq("tv"), eq("Ted Lasso"), any())).thenReturn(TV_HIT);
        when(api.getDetails(anyString(), eq("tv"), anyInt())).thenReturn(TV_DETAILS);
        when(api.getAlternativeTitles(anyString(), eq("tv"), eq(97546))).thenReturn(aliasesJson);
        return api;
    }

    @Test
    void 有CN条目时取CN() throws Exception {
        TMDbApiService api = apiWithAliases("{\"results\":["
                + "{\"iso_3166_1\":\"TW\",\"title\":\"泰德拉索\"},"
                + "{\"iso_3166_1\":\"CN\",\"title\":\"足球教练\"}]}");

        assertEquals("足球教练", client.search("tv", tvInfo(), api));
    }

    /** 只在台/港登记中文名的作品：早先只认 CN，这里会退回英文 */
    @Test
    void 只有TW条目时也要取到() throws Exception {
        TMDbApiService api = apiWithAliases("{\"results\":["
                + "{\"iso_3166_1\":\"US\",\"title\":\"Ted Lasso: The Richmond Way\"},"
                + "{\"iso_3166_1\":\"TW\",\"title\":\"泰德拉索\"}]}");

        assertEquals("泰德拉索", client.search("tv", tvInfo(), api));
    }

    /** 别名是众包数据：CN 条目登记拼音/英文的不在少数，只按地区取会把英文换成另一串英文 */
    @Test
    void CN条目不含中文时_跳过它继续看TW() throws Exception {
        TMDbApiService api = apiWithAliases("{\"results\":["
                + "{\"iso_3166_1\":\"CN\",\"title\":\"Ted Lasso\"},"
                + "{\"iso_3166_1\":\"TW\",\"title\":\"泰德拉索\"}]}");

        assertEquals("泰德拉索", client.search("tv", tvInfo(), api));
    }

    @Test
    void 没有任何中文别名时_退回候选自身的名字() throws Exception {
        TMDbApiService api = apiWithAliases("{\"results\":["
                + "{\"iso_3166_1\":\"KR\",\"title\":\"테드 래소\"}]}");

        assertEquals("Ted Lasso", client.search("tv", tvInfo(), api));
    }
}
