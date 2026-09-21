package com.osr.openliststrm.rename.rule;

import com.osr.common.utils.StringUtils;

/**
 * 「这个文件最后落到哪个目录」里与规则无关的那一半：目标库顶层目录名、以及一条规则都没命中时的兜底目录名。
 * <p>
 * 单独抽出来是因为它有**两个调用方**：真正执行重命名的 {@code MediaRenameProcessor#buildDestPath}，
 * 以及「重命名测试」接口——后者要在不落地任何文件的前提下算出同一条路径给用户看。两边各写一份
 * {@code "movie".equals(mediaType) ? "电影" : "电视剧"} 的话，改动一侧不会有任何报错，
 * 只是预览出来的路径与实际落盘的路径不一致，而那正是这个预览存在的全部意义。
 */
public final class CategoryPlacement {

    /** 电影的顶层目录名 */
    public static final String MOVIE_TOP_LEVEL = "电影";

    /** 剧集的顶层目录名 */
    public static final String TV_TOP_LEVEL = "电视剧";

    /** 一条规则都没命中（或压根没配规则）时用的目录名 */
    public static final String DEFAULT_CATEGORY = "未分类";

    private CategoryPlacement() {
    }

    /** 目标库顶层目录：movie 走「电影」，其余（tv）走「电视剧」 */
    public static String topLevelOf(String mediaType) {
        return "movie".equals(mediaType) ? MOVIE_TOP_LEVEL : TV_TOP_LEVEL;
    }

    /** 分类目录名，空则退回兜底目录 */
    public static String categoryOrDefault(String category) {
        return StringUtils.isNotBlank(category) ? category : DEFAULT_CATEGORY;
    }

    /**
     * 推断媒体类型：解析出季号或集号就算剧集，否则算电影。
     * <p>
     * 重命名任务本身不配置 mediaType（{@code RenameTaskPlus} 里没有这个字段），
     * 真正执行重命名时也是这么判的（{@code MediaRenameProcessor#processFile}），
     * 「重命名测试」必须用同一份判定——两边分头写的话，预览说电影、实际落进电视剧，
     * 而用户看不出是哪一边错了。判据用 null 而不是 blank，与原实现保持逐字一致。
     */
    public static String inferMediaType(String season, String episode) {
        return (season != null || episode != null) ? "tv" : "movie";
    }
}
