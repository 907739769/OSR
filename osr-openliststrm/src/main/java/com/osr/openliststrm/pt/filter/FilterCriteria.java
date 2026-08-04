package com.osr.openliststrm.pt.filter;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.pt.model.TorrentInfo;
import lombok.Builder;

import java.util.Arrays;
import java.util.List;

/**
 * 生效的过滤与排序条件——全局配置与订阅级覆盖合并后的最终结果。
 * <p>
 * 不可变。过滤引擎只认本类型，不直接读数据库，因此引擎是纯函数、可密集单测。
 * </p>
 * <p>
 * 分量已经多到位置参数不可读，一律用 {@link #builder()} 构造：新增一个维度时，
 * 既有调用方不必逐个补 {@code List.of()} 占位，也不会因为参数顺序写反而静默错配
 * （相邻的几个分量都是 {@code List<String>}，编译器帮不上忙）。
 * </p>
 *
 * @param minSeeders        最低做种数，低于此值淘汰
 * @param minSize           最小体积(字节)，0 表示不限
 * @param maxSize           最大体积(字节)，0 表示不限
 * @param freeOnly          是否仅要免费种
 * @param includeKeywords   标题须命中其一，空列表表示不限
 * @param excludeKeywords   标题命中任一则淘汰
 * @param resolutionPriority 分辨率优先级，越靠前越优先
 * @param resolutionWhitelist 分辨率白名单，非空时硬性过滤——不在白名单里的直接淘汰；空列表表示不限
 * @param sourceWhitelist   媒介来源白名单(WEB-DL/BluRay/REMUX 等)，语义同分辨率白名单；空列表表示不限
 * @param sourcePriority    媒介来源优先级，越靠前越优先，只影响排序
 * @param requiredTags      必需的质量标签，种子须<b>全部</b>具备；空列表表示不限
 * @param excludeTags       命中任一则淘汰的质量标签
 * @param releaseGroupPriority 发布组优先级，越靠前越优先，只影响排序；不在列表内的排最后
 * @param sortPriority      排序维度顺序；传空列表时回退到 {@link #DEFAULT_SORT_PRIORITY}
 * @param preferredSize     体积接近度的目标值(字节)，0 表示该维度不参与比较
 * @param sizePerEpisode    体积三项阈值(minSize/maxSize/preferredSize)是否按<b>每集</b>而非整个种子判定。
 *                          剧集的种子常常是区间包或季包，体积是单集的几倍到几十倍，直接拿整包体积去比
 *                          单集阈值：上限会把所有多集包一刀切光，下限会把所有单集资源放行，
 *                          SIZE 排序维度在单集与包混排的候选里也纯粹是噪声。开启后一律折算成
 *                          {@link com.osr.openliststrm.pt.model.TorrentInfo#getSizePerEpisode()} 再比较；
 *                          单集资源折算前后完全一致，因此这个开关只影响多集包
 * @param requireChineseSubtitle 外语电影(originalLanguage 不以 zh 开头)是否需要中文字幕标识
 * @param avoidHitAndRun    是否直接淘汰来自 H&R 考核站点的种子。默认关闭——H&R 站点往往正是资源最好的站点，
 *                          多数用户要的是"优先用没有考核的"而不是"完全不用"，那个诉求由
 *                          {@link SortDimension#HR} 降权维度满足；这里是给不愿承担任何保种义务的用户的硬开关
 * @author Jack
 */
@Builder
public record FilterCriteria(
        int minSeeders,
        long minSize,
        long maxSize,
        boolean freeOnly,
        List<String> includeKeywords,
        List<String> excludeKeywords,
        List<String> resolutionPriority,
        List<String> resolutionWhitelist,
        List<String> sourceWhitelist,
        List<String> sourcePriority,
        List<String> requiredTags,
        List<String> excludeTags,
        List<String> releaseGroupPriority,
        List<SortDimension> sortPriority,
        long preferredSize,
        boolean sizePerEpisode,
        boolean requireChineseSubtitle,
        boolean avoidHitAndRun) {

    /**
     * 按当前条件，这条种子参与体积判定的取值：开了每集折算就取每集体积，否则取整包体积。
     * <p>
     * 硬性过滤（{@link TorrentFilterEngine}）与 SIZE 排序维度（{@link SortDimension}）
     * 必须用同一个口径，否则会出现"能通过上限、排序却按整包体积算"这种自相矛盾的行为，
     * 所以取值逻辑收在条件对象自己身上，而不是各处各写一遍。
     * </p>
     */
    public long effectiveSize(TorrentInfo torrent) {
        return sizePerEpisode ? torrent.getSizePerEpisode() : torrent.getSize();
    }

    /** 未配置排序维度时的兜底顺序，与建表脚本的默认值一致 */
    public static final List<SortDimension> DEFAULT_SORT_PRIORITY =
            List.of(SortDimension.RESOLUTION, SortDimension.FREE, SortDimension.SEEDERS, SortDimension.SIZE);

    public FilterCriteria {
        // 防御性拷贝 + 不可变化：调用方之后修改传入的列表不应影响已构造的条件。
        // null 一律归一为空列表——与"空列表表示不限/回退默认"的既有语义保持一致，
        // 这也是 builder 未设置某个分量时走到的路径，因此不能指望调用方永远传非 null。
        includeKeywords = nullSafeCopy(includeKeywords);
        excludeKeywords = nullSafeCopy(excludeKeywords);
        resolutionPriority = nullSafeCopy(resolutionPriority);
        resolutionWhitelist = nullSafeCopy(resolutionWhitelist);
        sourceWhitelist = nullSafeCopy(sourceWhitelist);
        sourcePriority = nullSafeCopy(sourcePriority);
        requiredTags = nullSafeCopy(requiredTags);
        excludeTags = nullSafeCopy(excludeTags);
        releaseGroupPriority = nullSafeCopy(releaseGroupPriority);
        // 空的排序配置会让择优退化成"随便挑一个"，必须有兜底
        List<SortDimension> normalizedSortPriority = nullSafeCopy(sortPriority);
        sortPriority = normalizedSortPriority.isEmpty() ? DEFAULT_SORT_PRIORITY : normalizedSortPriority;
    }

    private static <T> List<T> nullSafeCopy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * 把逗号分隔的配置串切成列表，逐项去空白、丢弃空项。
     * 输入为 null 或空白时返回空列表。
     */
    public static List<String> splitCsv(String csv) {
        if (StringUtils.isBlank(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
