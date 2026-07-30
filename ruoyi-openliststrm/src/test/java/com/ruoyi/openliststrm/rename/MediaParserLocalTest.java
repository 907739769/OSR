package com.ruoyi.openliststrm.rename;

import com.ruoyi.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * parseLocal 的行为：只做本地正则抽取，不发任何网络请求。
 * 构造时传 null 的 tmdbClient 与 openAIClient——若实现里意外调了它们，测试会以 NPE 失败。
 */
class MediaParserLocalTest {

    private final MediaParser parser = new MediaParser(null, null);

    // 注：以下几个用例的季/集断言，从最初简报里的 "1"/"5"/"3"/"7" 调整为了 "01"/"05"/"03"/"07"。
    // 现有解析器 YearSeasonEpisodeExtractor.formatNumber() 会把季号、集号统一用 %02d 补零，
    // 这是既有重命名链路里的行为（不属于本任务能改动的范围），parseLocal 只是复用 extractBase，
    // 所以照实记录，不放宽断言。

    @Test
    void parseLocal_标准剧集命名_抽出季集与分辨率() {
        MediaInfo info = parser.parseLocal("Some.Show.S01E05.1080p.WEB-DL.H264-GROUP.mkv");

        // 现有解析器对季/集统一补零到两位：S01E05 -> season="01", episode="05"
        assertEquals("01", info.getSeason());
        assertEquals("05", info.getEpisode());
        assertEquals("1080p", info.getResolution());
    }

    @Test
    void parseLocal_不发网络请求_传null客户端也不抛异常() {
        // 若实现里调了 tmdbClient.enrich 或 openAIClient.enrich，这里会 NPE
        assertNotNull(parser.parseLocal("Some.Show.S02E10.2160p.BluRay.mkv"));
    }

    @Test
    void parseLocal_不做TMDb富化_tmdbId为空() {
        MediaInfo info = parser.parseLocal("Some.Show.S01E01.1080p.mkv");

        assertNull(info.getTmdbId());
    }

    @Test
    void parseLocal_电影命名_抽出年份且无季集() {
        MediaInfo info = parser.parseLocal("Fight.Club.1999.1080p.BluRay.x264.mkv");

        assertEquals("1999", info.getYear());
        assertNull(info.getSeason());
        assertNull(info.getEpisode());
    }

    @Test
    void parseLocal_中文剧名_能抽出季集() {
        MediaInfo info = parser.parseLocal("大明王朝1566.S01E12.2160p.WEB-DL.mkv");

        // 现有解析器对季号统一补零到两位：S01 -> "01"（集号 12 本身已是两位，不受影响）
        assertEquals("01", info.getSeason());
        assertEquals("12", info.getEpisode());
    }

    @Test
    void parseLocal_无扩展名的种子标题_同样能解析() {
        // RSS 里的 title 是种子名，通常没有文件扩展名
        MediaInfo info = parser.parseLocal("Some.Show.S03E07.1080p.WEB-DL");

        // 现有解析器对季/集统一补零到两位：S03E07 -> season="03", episode="07"
        assertEquals("03", info.getSeason());
        assertEquals("07", info.getEpisode());
    }

    @Test
    void parseLocal_季包命名_有季无集() {
        MediaInfo info = parser.parseLocal("Some.Show.S01.1080p.WEB-DL.COMPLETE");

        // 现有解析器对季号统一补零到两位：S01 -> "01"
        assertEquals("01", info.getSeason());
        assertNull(info.getEpisode());
    }

    @Test
    void parseLocal_集数区间_S01E01dash03_识别为区间() {
        MediaInfo info = parser.parseLocal("Some.Show.S01E01-03.1080p.WEB-DL");

        assertEquals("01", info.getSeason());
        assertEquals("01", info.getEpisode());
        assertEquals("03", info.getEpisodeEnd());
    }

    @Test
    void parseLocal_集数区间_S01E01dashE03_识别为区间() {
        MediaInfo info = parser.parseLocal("Some.Show.S01E01-E03.1080p.WEB-DL");

        assertEquals("01", info.getSeason());
        assertEquals("01", info.getEpisode());
        assertEquals("03", info.getEpisodeEnd());
    }

    @Test
    void parseLocal_单集无区间后缀_episodeEnd为空() {
        MediaInfo info = parser.parseLocal("Some.Show.S01E01.1080p.WEB-DL");

        assertEquals("01", info.getEpisode());
        assertNull(info.getEpisodeEnd());
    }

    @Test
    void parseLocal_区间结尾不大于起始_不当作区间处理() {
        // 起始集号本身两位数字后紧跟连字符+数字的噪声（如误配的分辨率片段），
        // 结尾 <= 起始时不应被当成区间
        MediaInfo info = parser.parseLocal("Some.Show.S01E05-01.1080p.WEB-DL");

        assertEquals("05", info.getEpisode());
        assertNull(info.getEpisodeEnd());
    }

    @Test
    void parseLocal_多集拼接_S01E01E02E03_识别为区间() {
        MediaInfo info = parser.parseLocal("Some.Show.S01E01E02E03.1080p.WEB-DL");

        assertEquals("01", info.getSeason());
        assertEquals("01", info.getEpisode());
        assertEquals("03", info.getEpisodeEnd());
    }

    @Test
    void parseLocal_无季号集数区间_E01dash03_识别为区间() {
        MediaInfo info = parser.parseLocal("Some.Show.E01-03.1080p.WEB-DL");

        assertEquals("01", info.getSeason());
        assertEquals("01", info.getEpisode());
        assertEquals("03", info.getEpisodeEnd());
    }

    @Test
    void parseLocal_无季号多集拼接_E01E02E03_识别为区间() {
        MediaInfo info = parser.parseLocal("Some.Show.E01E02E03.1080p.WEB-DL");

        assertEquals("01", info.getSeason());
        assertEquals("01", info.getEpisode());
        assertEquals("03", info.getEpisodeEnd());
    }

    @Test
    void parseLocal_保留原始名称() {
        String raw = "Some.Show.S01E05.1080p.mkv";

        assertEquals(raw, parser.parseLocal(raw).getOriginalName());
    }
}
