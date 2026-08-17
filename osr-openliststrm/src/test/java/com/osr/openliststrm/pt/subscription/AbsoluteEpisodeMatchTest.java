package com.osr.openliststrm.pt.subscription;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.dto.MatchResult;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按绝对集号发布的种子能否匹配到正确的一集。
 * <p>
 * 真实场景：《航海王》订阅的是 TMDb 第 23 季（本地集号 1..26，绝对号 1156..1181），
 * 而 PT 站上这类资源标的是 S01 + 绝对号——用户实际给的标题：
 * {@code One Piece S01E1173 1999 2160p WEB-DL H265 AAC-ADWeb}，它其实是第 23 季第 18 集。
 * 改动前季号 1≠23 会让它在匹配第一步就被淘汰，现象是「站上明明有，OSR 就是搜不到」。
 * </p>
 */
class AbsoluteEpisodeMatchTest {

    private final SubscriptionMatcher matcher = new SubscriptionMatcher();
    private final MediaParser parser = new MediaParser(null, null);

    /** 航海王第 23 季：本地 1..26 ↔ 绝对 1156..1181 */
    private static AbsoluteEpisodeMap onePieceSeason23() {
        List<PtSubscriptionEpisodePlus> episodes = new ArrayList<>();
        for (int i = 1; i <= 26; i++) {
            PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
            ep.setEpisode(i);
            ep.setTmdbEpisodeNumber(1155 + i);
            episodes.add(ep);
        }
        return AbsoluteEpisodeMap.from(episodes);
    }

    private static PtSubscriptionPlus onePiece() {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(84);
        sub.setMediaType("TV");
        sub.setTitle("航海王");
        sub.setEnglishTitle("One Piece");
        sub.setSeason(23);
        sub.setYear("1999");
        return sub;
    }

    /** 用真实解析器填充，避免测试里手工编造出解析器实际给不出的结果 */
    private TorrentInfo torrent(String title) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        MediaInfo info = parser.parseLocal(title);
        t.setParsedTitle(info.getOriginalTitle());
        t.setParsedTitleEn(info.getEnglishTitle());
        t.setParsedYear(info.getYear());
        t.setParsedSeason(info.getSeason() == null ? null : Integer.valueOf(info.getSeason()));
        t.setParsedEpisode(info.getEpisode() == null ? null : Integer.valueOf(info.getEpisode()));
        return t;
    }

    private MatchResult match(String title) {
        return matcher.match(torrent(title), List.of(onePiece()), Map.of(84, onePieceSeason23()));
    }

    @Test
    void 用户实际遇到的标题_S01加绝对号_匹配到第18集() {
        MatchResult result = match("One Piece S01E1173 1999 2160p WEB-DL H265 AAC-ADWeb");

        assertNotNull(result, "改动前就是这里返回 null，导致站上有资源却匹配不上");
        assertEquals(18, result.getEpisode(), "绝对号 1173 = 1156 + 17 → 第 23 季第 18 集");
    }

    @Test
    void 常规季集命名依然走原路径() {
        MatchResult result = match("One Piece S23E13 Episode 1168 1080p CR WEB-DL DDP 2.0 H.264-Kitsune");

        assertNotNull(result);
        assertEquals(13, result.getEpisode());
    }

    @Test
    void 不属于本季的绝对号不匹配() {
        // 1100 是第 22 季及更早的集，不在本订阅的映射里
        assertNull(match("One Piece S01E1100 1080p WEB-DL"));
    }

    @Test
    void 季号既不是1也不等于订阅季号时不猜() {
        // 标着 S02 的多半真是第 2 季，按绝对号解释风险太大
        assertNull(match("One Piece S02E1173 1080p WEB-DL"));
    }

    @Test
    void 标着S01的季包不匹配_否则会去拉一千多集() {
        assertNull(match("One Piece S01 1080p WEB-DL Complete"));
    }

    @Test
    void 普通剧集不启用绝对匹配() {
        // 映射为空（TMDb 集号与本地集号相同）时，S01E05 不该被第 3 季认领
        PtSubscriptionPlus normal = new PtSubscriptionPlus();
        normal.setId(1);
        normal.setMediaType("TV");
        normal.setTitle("绝命毒师");
        normal.setEnglishTitle("Breaking Bad");
        normal.setSeason(3);

        List<PtSubscriptionEpisodePlus> same = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
            ep.setEpisode(i);
            ep.setTmdbEpisodeNumber(i);
            same.add(ep);
        }
        assertNull(matcher.match(torrent("Breaking Bad S01E05 1080p WEB-DL"), List.of(normal),
                Map.of(1, AbsoluteEpisodeMap.from(same))));
    }

    // ---------- 绝对号 ↔ 本地集号 双向换算 ----------

    @Test
    void 本地集号可换算回绝对号_供拼检索关键词用() {
        AbsoluteEpisodeMap map = onePieceSeason23();

        assertEquals(1174, map.toAbsolute(19), "第 19 集 = 绝对号 1174");
        assertEquals(1156, map.toAbsolute(1));
        assertNull(map.toAbsolute(99), "不属于本季的集号换算不出绝对号");
        assertNull(map.toAbsolute(null));
    }

    @Test
    void 普通剧集换算不出绝对号_不会多打一次检索() {
        // 映射为空（TMDb 集号与本地集号相同），absoluteKeywords 据此跳过额外检索
        List<PtSubscriptionEpisodePlus> same = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
            ep.setEpisode(i);
            ep.setTmdbEpisodeNumber(i);
            same.add(ep);
        }
        assertTrue(AbsoluteEpisodeMap.from(same).isEmpty());
    }

    @Test
    void 中文第N集写法也能走绝对匹配() {
        // 解析器把「第1173集」识别成 season=1 + ep=1173，正好落进绝对匹配的判据
        MatchResult result = match("航海王 第1173集 1080p WEB-DL");

        assertNotNull(result);
        assertEquals(18, result.getEpisode());
    }

    @Test
    void 字幕组裸数字命名暂不支持_卡在标题解析而非集号判定() {
        // [Sakurato] One Piece - 1173 [2160p] 会被解析成标题「[ Sakurato ] One Piece - 1173 [ ] [ - ]」，
        // 在标题匹配那一步就被淘汰，走不到绝对号判定。这里把现状钉住：
        // 哪天有人修了 YearSeasonEpisodeExtractor 的标题截断，这条会变红，提示同步放开集号侧
        assertNull(match("[Sakurato] One Piece - 1173 [2160p][HEVC-10bit AAC]"));
    }
}
