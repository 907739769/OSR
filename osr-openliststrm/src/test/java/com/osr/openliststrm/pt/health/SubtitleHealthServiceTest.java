package com.osr.openliststrm.pt.health;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.health.SubtitleHealthService.SubtitleReport;
import com.osr.openliststrm.pt.health.SubtitleHealthService.Verdict;
import com.osr.openliststrm.pt.media.IMediaServerClient;
import com.osr.openliststrm.pt.media.MediaServerClientFactory;
import com.osr.openliststrm.pt.media.MediaStreamInfo;
import com.osr.openliststrm.pt.media.MediaStreamInfo.SubtitleTrack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubtitleHealthServiceTest {

    private final IPtSubscriptionPlusService subscriptionService = mock(IPtSubscriptionPlusService.class);
    private final IPtSubscriptionEpisodePlusService episodeService = mock(IPtSubscriptionEpisodePlusService.class);
    private final IPtMediaServerPlusService mediaServerService = mock(IPtMediaServerPlusService.class);
    private final MediaServerClientFactory clientFactory = mock(MediaServerClientFactory.class);
    private final IMediaServerClient client = mock(IMediaServerClient.class);
    private final SubtitleHealthService service =
            new SubtitleHealthService(subscriptionService, episodeService, mediaServerService, clientFactory);

    private static PtSubscriptionPlus sub(int id, String originalTitle) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setTitle("剧" + id);
        s.setOriginalTitle(originalTitle);
        s.setMediaType("TV");
        s.setSeason(1);
        s.setTmdbId(String.valueOf(1000 + id));
        return s;
    }

    private static PtSubscriptionEpisodePlus ep(int subId, int no) {
        PtSubscriptionEpisodePlus e = new PtSubscriptionEpisodePlus();
        e.setSubId(subId);
        e.setEpisode(no);
        e.setState("IN_LIBRARY");
        return e;
    }

    private static PtMediaServerPlus server(int id, String name) {
        PtMediaServerPlus s = new PtMediaServerPlus();
        s.setId(id);
        s.setName(name);
        s.setType("EMBY");
        return s;
    }

    private static MediaStreamInfo streams(List<String> audio, SubtitleTrack... subs) {
        return new MediaStreamInfo(true, audio, Arrays.asList(subs));
    }

    private static SubtitleTrack track(String language, String title) {
        return new SubtitleTrack(language, title, false);
    }

    @Test
    void 华语作品跳过_日韩作品即便含汉字也不跳过() {
        assertTrue(SubtitleHealthService.isChineseOrigin(sub(1, "三体")));
        assertFalse(SubtitleHealthService.isChineseOrigin(sub(2, "進撃の巨人")), "有假名的是日语");
        assertFalse(SubtitleHealthService.isChineseOrigin(sub(3, "오징어 게임")));
        assertFalse(SubtitleHealthService.isChineseOrigin(sub(4, "The Wire")));
        assertFalse(SubtitleHealthService.isChineseOrigin(sub(5, null)), "原名缺失时宁可多报");
    }

    @Test
    void 判定_语言码或轨道标题认出中文都算有中字() {
        assertEquals(Verdict.CHINESE_SUBTITLE, SubtitleHealthService.classify(
                streams(List.of("eng"), track("eng", "English"), track("chi", "Chinese Simplified (SUBRIP)"))));
        assertEquals(Verdict.CHINESE_SUBTITLE, SubtitleHealthService.classify(
                streams(List.of("eng"), track("zh-TW", null))));
        assertEquals(Verdict.CHINESE_SUBTITLE, SubtitleHealthService.classify(
                streams(List.of("eng"), track("und", "简体"))), "语言码缺失时靠标题兜底");
        assertEquals(Verdict.CHINESE_SUBTITLE, SubtitleHealthService.classify(
                streams(List.of("eng"), track(null, "CHS&ENG"))));
    }

    @Test
    void 判定_有中文音轨不需要字幕() {
        assertEquals(Verdict.CHINESE_AUDIO, SubtitleHealthService.classify(
                streams(List.of("eng", "chi"), track("eng", "English"))));
        assertEquals(Verdict.CHINESE_AUDIO, SubtitleHealthService.classify(streams(List.of("yue"))));
    }

    @Test
    void 判定_只有外语字幕与认不出语言的字幕要分开() {
        assertEquals(Verdict.NO_CHINESE, SubtitleHealthService.classify(
                streams(List.of("eng"), track("eng", "English"), track("spa", "Español"))));
        assertEquals(Verdict.NO_CHINESE, SubtitleHealthService.classify(streams(List.of("jpn"))), "一条字幕都没有");
        assertEquals(Verdict.UNKNOWN_LANGUAGE, SubtitleHealthService.classify(
                streams(List.of("eng"), track("und", "Scene.S01E01"))), "Scene 里的 sc 不能误判成中文");
        assertEquals(Verdict.UNKNOWN_LANGUAGE, SubtitleHealthService.classify(
                streams(List.of("eng"), new SubtitleTrack(null, "", true))));
    }

    @Test
    void 判定_没解析过的文件不能说没字幕() {
        assertEquals(Verdict.NO_MEDIA_INFO, SubtitleHealthService.classify(MediaStreamInfo.NOT_PROBED));
    }

    @Test
    void 汇总_按集归类_不在库里的集不报() throws IOException {
        PtMediaServerPlus emby = server(1, "Emby");
        when(mediaServerService.listActive()).thenReturn(List.of(emby));
        when(clientFactory.get(any())).thenReturn(client);
        when(subscriptionService.list()).thenReturn(List.of(sub(1, "The Wire")));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(ep(1, 1), ep(1, 2), ep(1, 3), ep(1, 4), ep(1, 5)));
        when(client.listStreamInfo(any(), eq("1001"), eq(1), eq(false))).thenReturn(Map.of(
                1, streams(List.of("eng"), track("chi", null)),
                2, streams(List.of("eng"), track("eng", null)),
                3, streams(List.of("eng"), track("und", null)),
                4, MediaStreamInfo.NOT_PROBED));

        SubtitleReport report = service.report(s -> true);

        assertEquals(1, report.issues().size());
        SubtitleHealthService.SubtitleIssue issue = report.issues().get(0);
        assertEquals(List.of(2), issue.noChinese());
        assertEquals(List.of(3), issue.unknownLanguage());
        assertEquals(List.of(4), issue.noMediaInfo());
        assertEquals(1, report.checkedSubscriptions());
        assertEquals(5, report.checkedEpisodes());
        assertFalse(report.unsupported());
    }

    @Test
    void 汇总_多台服务器同一集取最可信的结果_一台不通不影响另一台() throws IOException {
        PtMediaServerPlus a = server(1, "Emby");
        PtMediaServerPlus b = server(2, "Jellyfin");
        IMediaServerClient clientB = mock(IMediaServerClient.class);
        when(mediaServerService.listActive()).thenReturn(List.of(a, b));
        when(clientFactory.get(same(a))).thenReturn(client);
        when(clientFactory.get(same(b))).thenReturn(clientB);
        when(subscriptionService.list()).thenReturn(List.of(sub(1, "The Wire"), sub(2, "Sugar")));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(ep(1, 1), ep(2, 1)));
        when(client.listStreamInfo(same(a), eq("1001"), any(), anyBoolean()))
                .thenReturn(Map.of(1, MediaStreamInfo.NOT_PROBED));
        when(clientB.listStreamInfo(same(b), eq("1001"), any(), anyBoolean()))
                .thenReturn(Map.of(1, streams(List.of("eng"), track("chi", null))));
        when(client.listStreamInfo(same(a), eq("1002"), any(), anyBoolean()))
                .thenReturn(Map.of(1, streams(List.of("eng"))));
        when(clientB.listStreamInfo(same(b), eq("1002"), any(), anyBoolean()))
                .thenThrow(new IOException("连接超时"));

        SubtitleReport report = service.report(s -> true);

        assertEquals(1, report.issues().size(), "剧1 在 B 上有中字，不报");
        assertEquals(2, report.issues().get(0).subId());
        assertEquals(List.of(1), report.issues().get(0).noChinese());
        assertEquals(List.of("Jellyfin：1 部作品查询失败（连接超时）"), report.failures());
    }

    @Test
    void 华语作品与没有TMDb编号的订阅不发请求() throws IOException {
        PtSubscriptionPlus noTmdb = sub(2, "The Wire");
        noTmdb.setTmdbId(null);
        when(mediaServerService.listActive()).thenReturn(List.of(server(1, "Emby")));
        when(subscriptionService.list()).thenReturn(List.of(sub(1, "三体"), noTmdb));

        SubtitleReport report = service.report(s -> true);

        assertTrue(report.issues().isEmpty());
        verify(episodeService, never()).list(any(Wrapper.class));
        verify(client, never()).listStreamInfo(any(), any(), any(), anyBoolean());
    }

    @Test
    void 没有服务器支持时标明不支持_而不是报成一切正常() throws IOException {
        when(mediaServerService.listActive()).thenReturn(List.of());
        assertTrue(service.report(s -> true).unsupported(), "一台都没配");

        when(mediaServerService.listActive()).thenReturn(List.of(server(1, "Plex")));
        when(clientFactory.get(any())).thenReturn(client);
        when(subscriptionService.list()).thenReturn(List.of(sub(1, "The Wire")));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(ep(1, 1)));
        when(client.listStreamInfo(any(), any(), any(), anyBoolean())).thenReturn(null);
        assertTrue(service.report(s -> true).unsupported(), "接口默认返回 null 即不支持");
    }

    @Test
    void 电影按集号0查且不传季号() throws IOException {
        PtSubscriptionPlus movie = sub(1, "Heat");
        movie.setMediaType("MOVIE");
        movie.setSeason(0);
        PtSubscriptionEpisodePlus e = ep(1, 0);
        when(mediaServerService.listActive()).thenReturn(List.of(server(1, "Emby")));
        when(clientFactory.get(any())).thenReturn(client);
        when(subscriptionService.list()).thenReturn(List.of(movie));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(e));
        when(client.listStreamInfo(any(), eq("1001"), eq(null), eq(true)))
                .thenReturn(Map.of(0, streams(List.of("eng"), track("eng", null))));

        SubtitleReport report = service.report(s -> true);

        assertEquals(List.of(0), report.issues().get(0).noChinese());
        assertEquals(null, report.issues().get(0).season());
    }
}
