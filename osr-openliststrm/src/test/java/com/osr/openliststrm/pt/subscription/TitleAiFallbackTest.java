package com.osr.openliststrm.pt.subscription;

import com.alibaba.fastjson2.JSONArray;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.openai.AiChatService;
import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 兜底：只查缓存不阻塞、问过的不再问、只补本地没解析出的字段、AI 的回复逐字段校验。
 */
class TitleAiFallbackTest {

    private AiChatService ai;
    private OpenlistConfig config;
    private TitleAiFallback fallback;

    @BeforeEach
    void setUp() {
        ai = mock(AiChatService.class);
        config = mock(OpenlistConfig.class);
        when(ai.available()).thenReturn(true);
        when(config.getPtTitleAiFallback()).thenReturn("1");
        fallback = new TitleAiFallback(ai, config);
    }

    @Test
    void 首次未命中只排队_后台解析后命中_认不出的也缓存不再问() {
        assertNull(fallback.lookup("怪标题A"));
        assertNull(fallback.lookup("怪标题B"));
        when(ai.chatJson(anyString(), anyString(), anyInt())).thenReturn(JSONArray.parse(
                "[{\"i\":0,\"title\":\"凡人修仙传\",\"year\":\"2020\",\"season\":1,\"episode\":120},{\"i\":1,\"title\":null}]"));

        fallback.drainOnce();

        TitleAiFallback.Parsed a = fallback.lookup("怪标题A");
        assertEquals("凡人修仙传", a.title());
        assertEquals(120, a.episode());
        assertNull(fallback.lookup("怪标题B"), "认不出的返回 null");
        fallback.drainOnce();
        verify(ai, times(1)).chatJson(anyString(), anyString(), anyInt());
    }

    @Test
    void 开关关闭时不排队也不调用() {
        when(config.getPtTitleAiFallback()).thenReturn("0");
        assertNull(fallback.lookup("怪标题"));
        fallback.drainOnce();
        verify(ai, never()).chatJson(anyString(), anyString(), anyInt());
    }

    @Test
    void 调用失败不缓存_下次再看到会重新排队() {
        fallback.lookup("怪标题");
        when(ai.chatJson(anyString(), anyString(), anyInt())).thenReturn(null);
        fallback.drainOnce();

        fallback.lookup("怪标题");
        when(ai.chatJson(anyString(), anyString(), anyInt())).thenReturn(JSONArray.parse("[{\"i\":0,\"title\":\"某剧\"}]"));
        fallback.drainOnce();
        assertEquals("某剧", fallback.lookup("怪标题").title());
    }

    @Test
    void 需要兜底的判据() {
        TorrentInfo t = new TorrentInfo();
        assertTrue(TitleAiFallback.needsAi(t), "没有标题");
        t.setParsedTitle("Some Show");
        assertTrue(TitleAiFallback.needsAi(t), "季集年份全无");
        t.setParsedYear("2024");
        assertFalse(TitleAiFallback.needsAi(t));
    }

    @Test
    void 只补缺失字段_不覆盖本地结果() {
        TorrentInfo t = new TorrentInfo();
        t.setParsedTitle("本地标题");
        t.setParsedSeason(2);
        TitleAiFallback.apply(t, new TitleAiFallback.Parsed("AI标题", "2024", 3, 5, 8));

        assertEquals("本地标题", t.getParsedTitle());
        assertEquals(2, t.getParsedSeason());
        assertEquals("2024", t.getParsedYear());
        assertEquals(5, t.getParsedEpisode());
        assertEquals(8, t.getParsedEpisodeEnd());
    }

    @Test
    void 回复逐字段校验_编号越界与离谱取值丢掉() {
        List<String> titles = List.of("t0", "t1");
        Map<String, TitleAiFallback.Parsed> r = TitleAiFallback.parseReply(JSONArray.parse(
                "[{\"i\":0,\"title\":\"甲\",\"year\":\"99\",\"season\":\"2\",\"episode\":99999},"
                        + "{\"i\":5,\"title\":\"越界\"},{\"i\":1,\"title\":\"null\"}]"), titles);

        assertEquals(1, r.size());
        TitleAiFallback.Parsed p = r.get("t0");
        assertNull(p.year());
        assertEquals(2, p.season());
        assertNull(p.episode());
    }
}
