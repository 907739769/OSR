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
        return FilterCriteria.builder()
                .resolutionPriority(List.of("2160p", "1080p", "720p"))
                .sortPriority(sortPriority)
                .preferredSize(preferredSize)
                .build();
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

        FilterCriteria criteria = FilterCriteria.builder()
                .minSeeders(10)
                .resolutionPriority(List.of("2160p", "1080p", "720p"))
                .sortPriority(List.of(SortDimension.RESOLUTION))
                .build();

        List<TorrentInfo> survivors = engine.filter(
                List.of(eliminatedButOtherwisePerfect, survivorLowerResolution, survivorHigherResolution),
                criteria, TorrentBlacklist.EMPTY, null);
        TorrentInfo best = engine.pickBest(survivors, criteria);

        assertEquals(2, survivors.size(), "做种数 2 低于下限 10，应在 filter 阶段被淘汰");
        assertEquals("存活且分辨率较高", best.getTitle(),
                "赢家应是存活候选中分辨率最高的那个，而不是被淘汰的\"完美种\"");
    }

    /**
     * 做种数下限配成 0（不限）时，0 做种的候选合法地进入择优池——此时不能让它靠分辨率赢。
     * <p>
     * 这是真实事故的回归用例：缺集体检批量开启自动补搜后，补搜把老剧的死种翻了出来，
     * 默认排序 {@code RESOLUTION,FREE,SEEDERS,SIZE} 把分辨率排在做种数之前，
     * 于是分辨率最高的那个死种赢下择优、推给下载器后一动不动，占着并发名额直到僵尸超时。
     * </p>
     */
    @Test
    void 有活种可选时_分辨率最高的死种不该赢() {
        List<TorrentInfo> candidates = List.of(
                torrent("4K但无人做种", "2160p", true, 0, 5_000_000_000L),
                torrent("1080有人做种", "1080p", false, 8, 3_000_000_000L));

        // 做种数下限 0（不限），排序按默认的分辨率优先
        FilterCriteria criteria = FilterCriteria.builder()
                .minSeeders(0)
                .resolutionPriority(List.of("2160p", "1080p", "720p"))
                .sortPriority(List.of(SortDimension.RESOLUTION, SortDimension.FREE, SortDimension.SEEDERS))
                .build();

        List<TorrentInfo> survivors = engine.filter(candidates, criteria, TorrentBlacklist.EMPTY, null);
        TorrentInfo best = engine.pickBest(survivors, criteria);

        assertEquals(2, survivors.size(), "下限为 0 时 0 做种不该被硬过滤淘汰");
        assertEquals("1080有人做种", best.getTitle(),
                "「有人做种」自成一档，排在分辨率之前——下不下来与好不好不是同一个量纲");
    }

    /**
     * 分档只在「有活种可选」时生效：全场都没人做种时（部分索引器压根不返回 seeders，
     * 解析后全是 0），所有候选同档，配置的维度顺序照常决定赢家。
     */
    @Test
    void 全场都无人做种时_维度顺序照常决定赢家() {
        List<TorrentInfo> candidates = List.of(
                torrent("720死种", "720p", false, 0, 1_000_000_000L),
                torrent("4K死种", "2160p", false, 0, 5_000_000_000L));

        TorrentInfo best = engine.pickBest(candidates,
                criteria(List.of(SortDimension.RESOLUTION), 0L));

        assertEquals("4K死种", best.getTitle());
    }

    /**
     * 手动搜索候选列表用 {@code sortComparator} 排序、自动推送用 {@code pickBest} 择优，
     * 两者必须是同一口径：否则用户在列表里看到排第一的那个，与自动推送实际选中的不是同一个种子。
     */
    @Test
    void 列表排序与择优同一口径() {
        FilterCriteria criteria = criteria(
                List.of(SortDimension.RESOLUTION, SortDimension.FREE, SortDimension.SEEDERS), 0L);
        List<TorrentInfo> candidates = new ArrayList<>(List.of(
                torrent("4K但无人做种", "2160p", true, 0, 5_000_000_000L),
                torrent("1080有人做种", "1080p", false, 8, 3_000_000_000L),
                torrent("720多做种", "720p", false, 200, 2_000_000_000L)));

        TorrentInfo best = engine.pickBest(candidates, criteria);
        candidates.sort(engine.sortComparator(criteria));

        assertEquals(best.getTitle(), candidates.get(0).getTitle());
        assertEquals("1080有人做种", candidates.get(0).getTitle());
    }
}
