package com.osr.openliststrm.pt.model;

import com.osr.common.utils.StringUtils;

import java.util.List;

/**
 * 种子与订阅两侧外部 ID（IMDb / TMDb）的归一化，以及 Torznab 分类的粗粒度判定。
 * <p>
 * 两侧的写法并不一致：订阅存的是 TMDb 详情里的 {@code tt0944947}，而 Jackett 的 {@code imdb}
 * 属性给的是去掉前缀的 {@code 0944947}、有的版本连前导零都没有（{@code 944947}），Prowlarr 的
 * {@code imdbid} 又带着 {@code tt}。不归一化就直接比，同一部作品会被判成「ID 不一致」——
 * 而 ID 不一致在匹配侧是<b>否决</b>信号，那等于把一批完全正确的种子静默挡掉。
 * </p>
 *
 * @author Jack
 */
public final class ExternalIds {

    /** IMDb 编号至少补足到 7 位，与 imdb.com 的规范写法一致（{@code tt0944947}） */
    private static final int IMDB_MIN_DIGITS = 7;

    private ExternalIds() {
    }

    /**
     * {@code tt0944947} / {@code 0944947} / {@code 944947} → {@code tt0944947}。
     * 空、非数字、全零一律返回 null——索引器给 {@code 0} 的语义是「没有」，不是一个 ID。
     */
    public static String normalizeImdb(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String digits = raw.trim();
        if (digits.regionMatches(true, 0, "tt", 0, 2)) {
            digits = digits.substring(2);
        }
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        String stripped = digits.replaceFirst("^0+", "");
        if (stripped.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("tt");
        for (int i = stripped.length(); i < IMDB_MIN_DIGITS; i++) {
            sb.append('0');
        }
        return sb.append(stripped).toString();
    }

    /** {@code 1399} → {@code 1399}；空、非数字、0 返回 null（理由同 {@link #normalizeImdb}） */
    public static String normalizeTmdb(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String digits = raw.trim();
        if (!digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        String stripped = digits.replaceFirst("^0+", "");
        return stripped.isEmpty() ? null : stripped;
    }

    /** Torznab 分类所属的大类 */
    public enum Kind {
        MOVIE, TV, UNKNOWN
    }

    /**
     * 按 Newznab 标准分类判大类：2000~2999 电影、5000~5999 剧集。
     * <p>
     * 两类都出现或都没出现时返回 {@link Kind#UNKNOWN}。这个判定只服务于一件事——
     * <b>TMDb ID 的电影与剧集是两套独立编号</b>（movie/1399 与 tv/1399 是毫不相干的两部作品），
     * 分不清种子是哪一类时，它带的 tmdbid 就没法拿来和订阅比，只能当作没给。
     * Jackett 的站点自定义分类（100000 以上）不参与判断，Jackett 会同时附上映射后的标准分类。
     * </p>
     */
    public static Kind kindOf(List<Integer> categories) {
        if (categories == null || categories.isEmpty()) {
            return Kind.UNKNOWN;
        }
        boolean movie = false;
        boolean tv = false;
        for (Integer cat : categories) {
            if (cat == null) {
                continue;
            }
            if (cat >= 2000 && cat < 3000) {
                movie = true;
            } else if (cat >= 5000 && cat < 6000) {
                tv = true;
            }
        }
        if (movie == tv) {
            return Kind.UNKNOWN;
        }
        return movie ? Kind.MOVIE : Kind.TV;
    }
}
