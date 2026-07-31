package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TorrentFilterEnginePickBestTest {

    private final TorrentFilterEngine engine = new TorrentFilterEngine();

    private FilterCriteria criteria(List<SortDimension> sortPriority, long preferredSize) {
        return new FilterCriteria(0, 0L, 0L, false, List.of(), List.of(),
                List.of("2160p", "1080p", "720p"), List.of(), sortPriority, preferredSize, false);
    }

    private TorrentInfo torrent(String title, String resolution, boolean free, int seeders, long size) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setParsedResolution(resolution);
        t.setDownloadVolumeFactor(free ? 0.0 : 1.0);
        t.setSeeders(seeders);
        t.setSize(size);
        return t;
    }

    /** 三个候选，各维度上互有胜负，用于验证维度顺序真的决定结果 */
    private List<TorrentInfo> mixedCandidates() {
        return List.of(
                // 分辨率最高，但收费、做种少
                torrent("4K收费", "2160p", false, 3, 60_000_000_000L),
                // 分辨率中等，免费，做种中等
                torrent("1080免费", "1080p", true, 20, 5_000_000_000L),
                // 分辨率最低，收费，做种最多
                torrent("720多做种", "720p", false, 200, 2_000_000_000L));
    }

    @Test
    void 分辨率优先_选出4K() {
        TorrentInfo best = engine.pickBest(mixedCandidates(),
                criteria(List.of(SortDimension.RESOLUTION, SortDimension.FREE, SortDimension.SEEDERS), 0L));

        assertEquals("4K收费", best.getTitle());
    }

    @Test
    void 免费优先_选出1080免费() {
        // 宁可要免费的 1080p，也不要收费的 4K
        TorrentInfo best = engine.pickBest(mixedCandidates(),
                criteria(List.of(SortDimension.FREE, SortDimension.RESOLUTION, SortDimension.SEEDERS), 0L));

        assertEquals("1080免费", best.getTitle());
    }

    @Test
    void 做种数优先_选出720多做种() {
        TorrentInfo best = engine.pickBest(mixedCandidates(),
                criteria(List.of(SortDimension.SEEDERS, SortDimension.RESOLUTION), 0L));

        assertEquals("720多做种", best.getTitle());
    }

    @Test
    void 同一批候选_三种排序配置选出三个不同赢家() {
        // 这条是「排序权重可调」需求的核心实证
        List<TorrentInfo> candidates = mixedCandidates();

        String byResolution = engine.pickBest(candidates,
                criteria(List.of(SortDimension.RESOLUTION), 0L)).getTitle();
        String byFree = engine.pickBest(candidates,
                criteria(List.of(SortDimension.FREE, SortDimension.RESOLUTION), 0L)).getTitle();
        String bySeeders = engine.pickBest(candidates,
                criteria(List.of(SortDimension.SEEDERS), 0L)).getTitle();

        assertEquals("4K收费", byResolution);
        assertEquals("1080免费", byFree);
        assertEquals("720多做种", bySeeders);
    }

    @Test
    void 首维度同级时_由次维度决胜() {
        List<TorrentInfo> candidates = List.of(
                torrent("1080少做种", "1080p", false, 5, 100L),
                torrent("1080多做种", "1080p", false, 50, 100L));

        TorrentInfo best = engine.pickBest(candidates,
                criteria(List.of(SortDimension.RESOLUTION, SortDimension.SEEDERS), 0L));

        assertEquals("1080多做种", best.getTitle());
    }

    @Test
    void 体积接近度参与决胜() {
        List<TorrentInfo> candidates = List.of(
                torrent("超大", "1080p", false, 10, 60_000_000_000L),
                torrent("适中", "1080p", false, 10, 5_200_000_000L),
                torrent("过小", "1080p", false, 10, 100_000_000L));

        TorrentInfo best = engine.pickBest(candidates,
                criteria(List.of(SortDimension.SIZE), 5_000_000_000L));

        assertEquals("适中", best.getTitle());
    }

    @Test
    void 全部维度同级_返回第一个保持稳定() {
        List<TorrentInfo> candidates = List.of(
                torrent("先来的", "1080p", false, 10, 100L),
                torrent("后到的", "1080p", false, 10, 100L));

        TorrentInfo best = engine.pickBest(candidates,
                criteria(List.of(SortDimension.RESOLUTION, SortDimension.SEEDERS), 0L));

        assertEquals("先来的", best.getTitle());
    }

    @Test
    void 单个候选_直接返回它() {
        TorrentInfo only = torrent("唯一", "480p", false, 0, 1L);

        assertEquals("唯一", engine.pickBest(List.of(only), criteria(List.of(SortDimension.RESOLUTION), 0L)).getTitle());
    }

    @Test
    void 空候选_返回null() {
        assertNull(engine.pickBest(List.of(), criteria(List.of(SortDimension.RESOLUTION), 0L)));
    }

    @Test
    void 不修改入参列表的顺序() {
        List<TorrentInfo> candidates = new ArrayList<>(mixedCandidates());
        List<String> before = candidates.stream().map(TorrentInfo::getTitle).toList();

        engine.pickBest(candidates, criteria(List.of(SortDimension.SEEDERS), 0L));

        assertEquals(before, candidates.stream().map(TorrentInfo::getTitle).toList());
    }

    /**
     * 调用方的真实用法是「先 filter 淘汰不合格候选，再从存活候选里 pickBest」。
     * 这里构造一个各维度都最优（分辨率最高、免费）但做种数不达标的种子，
     * 用来证明它会先在 filter 阶段被淘汰、赢家只能从存活候选中产生——
     * 而不是 pickBest 单独在全量候选里选出这个本应出局的"完美种"。
     */
    @Test
    void filter淘汰后再pickBest_赢家必须来自存活候选而非被淘汰的最优种() {
        TorrentInfo eliminatedButOtherwisePerfect = torrent("做种不达标的完美种", "2160p", true, 2, 5_000_000_000L);
        TorrentInfo survivorLowerResolution = torrent("存活但分辨率较低", "720p", false, 15, 1_000_000_000L);
        TorrentInfo survivorHigherResolution = torrent("存活且分辨率较高", "1080p", false, 50, 3_000_000_000L);

        FilterCriteria criteria = new FilterCriteria(10, 0L, 0L, false, List.of(), List.of(),
                List.of("2160p", "1080p", "720p"), List.of(),
                List.of(SortDimension.RESOLUTION), 0L, false);

        List<TorrentInfo> survivors = engine.filter(
                List.of(eliminatedButOtherwisePerfect, survivorLowerResolution, survivorHigherResolution),
                criteria, (String) null);
        TorrentInfo best = engine.pickBest(survivors, criteria);

        assertEquals(2, survivors.size(), "做种数 2 低于下限 10，应在 filter 阶段被淘汰");
        assertEquals("存活且分辨率较高", best.getTitle(),
                "赢家应是存活候选中分辨率最高的那个，而不是被淘汰的\"完美种\"");
    }
}
