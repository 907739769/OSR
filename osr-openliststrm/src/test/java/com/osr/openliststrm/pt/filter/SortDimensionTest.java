package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortDimensionTest {

    private FilterCriteria criteria(List<String> resolutions, long preferredSize) {
        return FilterCriteria.builder()
                .resolutionPriority(resolutions)
                .sortPriority(List.of(SortDimension.SEEDERS))
                .preferredSize(preferredSize)
                .build();
    }

    // ---------- SOURCE / RELEASE_GROUP ----------

    private TorrentInfo torrentWithSource(String source) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle("t-source-" + source);
        t.setParsedSource(source);
        return t;
    }

    private TorrentInfo torrentWithGroup(String group) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle("t-group-" + group);
        t.setParsedReleaseGroup(group);
        return t;
    }

    @Test
    void SOURCE_按优先级列表排序_越靠前越优() {
        FilterCriteria c = FilterCriteria.builder()
                .sourcePriority(List.of("REMUX", "BluRay", "WEBDL"))
                .build();
        Comparator<TorrentInfo> comparator = SortDimension.SOURCE.comparator(c);

        assertTrue(comparator.compare(torrentWithSource("REMUX"), torrentWithSource("WEBDL")) < 0);
        assertTrue(comparator.compare(torrentWithSource("BluRay"), torrentWithSource("REMUX")) > 0);
    }

    @Test
    void SOURCE_不在列表中的排最后_解析不出的也一样() {
        FilterCriteria c = FilterCriteria.builder().sourcePriority(List.of("REMUX", "BluRay")).build();
        Comparator<TorrentInfo> comparator = SortDimension.SOURCE.comparator(c);

        assertTrue(comparator.compare(torrentWithSource("BluRay"), torrentWithSource("HDTV")) < 0);
        assertTrue(comparator.compare(torrentWithSource("BluRay"), torrentWithSource(null)) < 0);
        // 都排最后时判同级，不能凭空造出偏好
        assertEquals(0, comparator.compare(torrentWithSource("HDTV"), torrentWithSource(null)));
    }

    @Test
    void SOURCE_大小写不敏感() {
        FilterCriteria c = FilterCriteria.builder().sourcePriority(List.of("WEBDL")).build();

        assertTrue(SortDimension.SOURCE.comparator(c)
                .compare(torrentWithSource("webdl"), torrentWithSource("HDTV")) < 0);
    }

    @Test
    void SOURCE_未配置优先级_该维度不参与比较() {
        // 不能退化成任意顺序：没配就是没偏好，交给后面的维度决定
        FilterCriteria c = FilterCriteria.builder().sourcePriority(List.of()).build();

        assertEquals(0, SortDimension.SOURCE.comparator(c)
                .compare(torrentWithSource("REMUX"), torrentWithSource("HDTV")));
    }

    @Test
    void RELEASE_GROUP_按优先级列表排序() {
        FilterCriteria c = FilterCriteria.builder()
                .releaseGroupPriority(List.of("CHDBits", "FRDS"))
                .build();
        Comparator<TorrentInfo> comparator = SortDimension.RELEASE_GROUP.comparator(c);

        assertTrue(comparator.compare(torrentWithGroup("CHDBits"), torrentWithGroup("FRDS")) < 0);
        // 不在优先列表里的发布组只是排最后，不会被淘汰——淘汰是黑名单的职责
        assertTrue(comparator.compare(torrentWithGroup("FRDS"), torrentWithGroup("SomeUnknownGroup")) < 0);
    }

    @Test
    void RELEASE_GROUP_未配置优先级_该维度不参与比较() {
        FilterCriteria c = FilterCriteria.builder().releaseGroupPriority(List.of()).build();

        assertEquals(0, SortDimension.RELEASE_GROUP.comparator(c)
                .compare(torrentWithGroup("CHDBits"), torrentWithGroup("Unknown")));
    }

    @Test
    void HR_无考核的站点优先() {
        TorrentInfo clean = new TorrentInfo();
        clean.setTitle("clean");
        TorrentInfo hr = new TorrentInfo();
        hr.setTitle("hr");
        hr.setHitAndRun(true);
        Comparator<TorrentInfo> comparator = SortDimension.HR.comparator(FilterCriteria.builder().build());

        assertTrue(comparator.compare(clean, hr) < 0);
        // 同为 H&R（或同为非 H&R）时判同级，交给后面的维度决定
        assertEquals(0, comparator.compare(hr, hr));
    }

    @Test
    void 新增维度可被parseCsv识别() {
        assertEquals(List.of(SortDimension.SOURCE, SortDimension.RELEASE_GROUP, SortDimension.HR),
                SortDimension.parseCsv("SOURCE,RELEASE_GROUP,HR"));
    }

    private TorrentInfo torrent(String resolution, boolean free, int seeders, long size) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle("t-" + resolution + "-" + seeders + "-" + size);
        t.setParsedResolution(resolution);
        t.setDownloadVolumeFactor(free ? 0.0 : 1.0);
        t.setSeeders(seeders);
        t.setSize(size);
        return t;
    }

    private TorrentInfo torrentWithFactor(double downloadVolumeFactor) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle("t-factor-" + downloadVolumeFactor);
        t.setDownloadVolumeFactor(downloadVolumeFactor);
        return t;
    }

    // ---------- RESOLUTION ----------

    @Test
    void resolution_按优先级列表排序_靠前的更优() {
        Comparator<TorrentInfo> c = SortDimension.RESOLUTION.comparator(
                criteria(List.of("2160p", "1080p", "720p"), 0L));

        assertTrue(c.compare(torrent("2160p", false, 0, 0), torrent("1080p", false, 0, 0)) < 0);
        assertTrue(c.compare(torrent("720p", false, 0, 0), torrent("1080p", false, 0, 0)) > 0);
        assertEquals(0, c.compare(torrent("1080p", false, 0, 0), torrent("1080p", false, 0, 0)));
    }

    @Test
    void resolution_大小写不敏感() {
        Comparator<TorrentInfo> c = SortDimension.RESOLUTION.comparator(
                criteria(List.of("2160p", "1080p"), 0L));

        // 索引器给的标题里 1080P 与 1080p 都出现过
        assertEquals(0, c.compare(torrent("1080P", false, 0, 0), torrent("1080p", false, 0, 0)));
        assertTrue(c.compare(torrent("2160P", false, 0, 0), torrent("1080p", false, 0, 0)) < 0);
    }

    @Test
    void resolution_不在优先级列表中的排到最后() {
        Comparator<TorrentInfo> c = SortDimension.RESOLUTION.comparator(
                criteria(List.of("2160p", "1080p"), 0L));

        assertTrue(c.compare(torrent("480p", false, 0, 0), torrent("1080p", false, 0, 0)) > 0);
        assertTrue(c.compare(torrent(null, false, 0, 0), torrent("1080p", false, 0, 0)) > 0);
        // 两个都不在列表中时视为同级
        assertEquals(0, c.compare(torrent("480p", false, 0, 0), torrent(null, false, 0, 0)));
    }

    @Test
    void resolution_优先级列表为空_全部同级() {
        Comparator<TorrentInfo> c = SortDimension.RESOLUTION.comparator(criteria(List.of(), 0L));

        assertEquals(0, c.compare(torrent("2160p", false, 0, 0), torrent("480p", false, 0, 0)));
    }

    // ---------- FREE ----------

    @Test
    void free_免费种更优() {
        Comparator<TorrentInfo> c = SortDimension.FREE.comparator(criteria(List.of(), 0L));

        assertTrue(c.compare(torrent("1080p", true, 0, 0), torrent("1080p", false, 0, 0)) < 0);
        assertTrue(c.compare(torrent("1080p", false, 0, 0), torrent("1080p", true, 0, 0)) > 0);
        assertEquals(0, c.compare(torrent("1080p", true, 0, 0), torrent("1080p", true, 0, 0)));
    }

    @Test
    void free_五折优于全价() {
        // PT 站常见的半价促销种(downloadVolumeFactor=0.5)必须优于全价种，
        // 二值判断会把两者判同级，导致择优随机落到全价种上
        Comparator<TorrentInfo> c = SortDimension.FREE.comparator(criteria(List.of(), 0L));

        assertTrue(c.compare(torrentWithFactor(0.5), torrentWithFactor(1.0)) < 0);
    }

    @Test
    void free_全免优于五折() {
        Comparator<TorrentInfo> c = SortDimension.FREE.comparator(criteria(List.of(), 0L));

        assertTrue(c.compare(torrentWithFactor(0.0), torrentWithFactor(0.5)) < 0);
    }

    @Test
    void free_相同计量系数判同级() {
        Comparator<TorrentInfo> c = SortDimension.FREE.comparator(criteria(List.of(), 0L));

        assertEquals(0, c.compare(torrentWithFactor(0.5), torrentWithFactor(0.5)));
    }

    // ---------- SEEDERS ----------

    @Test
    void seeders_做种多者更优() {
        Comparator<TorrentInfo> c = SortDimension.SEEDERS.comparator(criteria(List.of(), 0L));

        assertTrue(c.compare(torrent("1080p", false, 50, 0), torrent("1080p", false, 3, 0)) < 0);
        assertTrue(c.compare(torrent("1080p", false, 0, 0), torrent("1080p", false, 1, 0)) > 0);
        assertEquals(0, c.compare(torrent("1080p", false, 7, 0), torrent("1080p", false, 7, 0)));
    }

    // ---------- SIZE ----------

    @Test
    void size_越接近偏好体积越优() {
        long preferred = 5_000_000_000L;
        Comparator<TorrentInfo> c = SortDimension.SIZE.comparator(criteria(List.of(), preferred));

        TorrentInfo close = torrent("1080p", false, 0, 5_100_000_000L);
        TorrentInfo far = torrent("1080p", false, 0, 60_000_000_000L);

        assertTrue(c.compare(close, far) < 0);
    }

    @Test
    void size_偏好体积两侧等距_视为同级() {
        long preferred = 5_000_000_000L;
        Comparator<TorrentInfo> c = SortDimension.SIZE.comparator(criteria(List.of(), preferred));

        assertEquals(0, c.compare(
                torrent("1080p", false, 0, 4_000_000_000L),
                torrent("1080p", false, 0, 6_000_000_000L)));
    }

    @Test
    void size_未配置偏好体积_该维度完全不参与比较() {
        Comparator<TorrentInfo> c = SortDimension.SIZE.comparator(criteria(List.of(), 0L));

        // 不能退化成"越小越好"，否则用户没配偏好体积时会莫名其妙总下到最小的那个
        assertEquals(0, c.compare(
                torrent("1080p", false, 0, 1_000L),
                torrent("1080p", false, 0, 90_000_000_000L)));
    }

    // ---------- 枚举解析 ----------

    @Test
    void parse_识别有效维度名_大小写不敏感() {
        assertEquals(List.of(SortDimension.FREE, SortDimension.SEEDERS),
                SortDimension.parseCsv("free, SEEDERS"));
    }

    @Test
    void parse_忽略无法识别的维度名_不抛异常() {
        // 配置是用户手输的，写错一个词不该让整个轮询挂掉
        assertEquals(List.of(SortDimension.SEEDERS), SortDimension.parseCsv("SEEDERS,不存在的维度"));
    }

    @Test
    void parse_空值_返回空列表() {
        assertTrue(SortDimension.parseCsv(null).isEmpty());
        assertTrue(SortDimension.parseCsv("").isEmpty());
    }
}
