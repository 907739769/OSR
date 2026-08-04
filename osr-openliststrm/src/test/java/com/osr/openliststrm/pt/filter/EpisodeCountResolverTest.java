package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodeCountResolverTest {

    private TorrentInfo torrent(Integer season, Integer episode, Integer episodeEnd) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle("Some.Show");
        t.setParsedSeason(season);
        t.setParsedEpisode(episode);
        t.setParsedEpisodeEnd(episodeEnd);
        return t;
    }

    @Test
    void 单集资源_恒为1() {
        assertEquals(1, EpisodeCountResolver.resolve(torrent(1, 5, null), 50, false));
    }

    @Test
    void 区间包_按区间长度算() {
        assertEquals(6, EpisodeCountResolver.resolve(torrent(1, 1, 6), 50, false));
    }

    @Test
    void 区间结尾不大于起始_按单集算() {
        // 与 SubscriptionMatcher 的口径一致：结尾必须严格大于起始才算区间
        assertEquals(1, EpisodeCountResolver.resolve(torrent(1, 5, 5), 50, false));
        assertEquals(1, EpisodeCountResolver.resolve(torrent(1, 5, 3), 50, false));
    }

    @Test
    void 整季包_按订阅总集数算() {
        // 标题里没有任何集数信息，只能取订阅的总集数
        assertEquals(50, EpisodeCountResolver.resolve(torrent(1, null, null), 50, false));
    }

    @Test
    void 整季包但总集数未知_退回1不折算() {
        // 估不出来时宁可不折算，也不能凭空编一个集数把体积判定引向另一个错误的方向
        assertEquals(1, EpisodeCountResolver.resolve(torrent(1, null, null), null, false));
        assertEquals(1, EpisodeCountResolver.resolve(torrent(1, null, null), 0, false));
    }

    @Test
    void 电影_恒为1() {
        // 电影没有集的概念，哪怕解析出了季集信息也不折算
        assertEquals(1, EpisodeCountResolver.resolve(torrent(null, null, null), 1, true));
        assertEquals(1, EpisodeCountResolver.resolve(torrent(1, 1, 6), 1, true));
    }

    @Test
    void apply就地填充整批候选() {
        TorrentInfo single = torrent(1, 5, null);
        TorrentInfo range = torrent(1, 1, 6);
        TorrentInfo pack = torrent(1, null, null);

        EpisodeCountResolver.apply(List.of(single, range, pack), 50, false);

        assertEquals(1, single.getEpisodeCount());
        assertEquals(6, range.getEpisodeCount());
        assertEquals(50, pack.getEpisodeCount());
    }
}
