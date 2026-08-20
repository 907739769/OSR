package com.osr.openliststrm.tmdb;

import java.util.List;

/**
 * TMDb {@code alternative_titles} 的地区优先级。
 * <p>
 * 单独抽出来是因为「挑中文别名」这件事有两个消费方：刮削/重命名侧的
 * {@link TMDbClient#fetchChineseAlias} 与 PT 订阅侧的
 * {@code TmdbSearchService#fetchChineseAlias}。两边各写一份的后果是口径漂移——
 * 早先重命名侧只认 CN，于是只在台/港登记了中文名的作品（日番、港片常见）在订阅列表里是中文、
 * 在媒体库里却是英文，而这种不一致从日志里根本看不出来。
 * </p>
 */
public final class TmdbTitleRegions {

    /** 中文别名的地区优先级（{@code iso_3166_1}）：大陆 → 台湾 → 香港 → 新加坡 */
    public static final List<String> CHINESE = List.of("CN", "TW", "HK", "SG");

    private TmdbTitleRegions() {
    }
}
