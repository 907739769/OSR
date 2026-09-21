package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.pt.upgrade.QualityProfile;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下拉的可选值必须覆盖解析器真实会产出的值。两边一旦漂移，用户在下拉里就选不到
 * 那个值，而手打又已经不允许了。
 */
class FilterVocabularyTest {

    private final MediaParser parser = new MediaParser(null, null);

    @ParameterizedTest
    @ValueSource(strings = {
            "Some.Show.S01E01.2160p.BluRay.REMUX.HDR10.Atmos.TrueHD.7.1.H265-GRP",
            "Some.Show.S01E02.1080p.WEB-DL.DDP5.1.H.264-CHDWEB",
            "Some.Movie.2023.720p.WEBRip.AAC.x264-GRP",
            "Some.Movie.2023.4K.HDTV.HEVC.10bit.DV.AC3-GRP",
            "Some.Movie.2023.1080i.BDRip.DTS-HD.MA.5.1.AVC-GRP",
            "Some.Movie.2023.8K.HDR10+.60fps.IMAX.FLAC-GRP",
            "Some.Movie.2023.576p.DVDRip.x264-GRP",
            "Some.Movie.2023.1440p.WEB.NF.EAC3.AV1-GRP"
    })
    void 解析结果都在可选值里(String title) {
        MediaInfo info = parser.parseLocal(title);
        if (info.getResolution() != null) {
            assertTrue(containsIgnoreCase(FilterVocabulary.RESOLUTIONS, info.getResolution()),
                    () -> "分辨率不在可选值里：" + info.getResolution());
        }
        String source = MediaSource.effective(info.getSource(), QualityProfile.collectTags(info));
        if (source != null) {
            assertTrue(containsIgnoreCase(FilterVocabulary.SOURCES, source), () -> "来源不在可选值里：" + source);
        }
        for (String tag : QualityProfile.collectTags(info)) {
            assertTrue(containsIgnoreCase(FilterVocabulary.TAGS, tag), () -> "标签不在可选值里：" + tag);
        }
    }

    private static boolean containsIgnoreCase(java.util.List<String> list, String value) {
        return list.stream().anyMatch(v -> v.equalsIgnoreCase(value));
    }
}
