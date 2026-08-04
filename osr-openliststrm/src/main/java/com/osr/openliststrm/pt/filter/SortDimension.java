package com.osr.openliststrm.pt.filter;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.pt.model.TorrentInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 择优时的排序维度。取值写入 pt_filter_config.sort_priority，逗号分隔。
 * <p>
 * 每个维度自带一个比较器，语义统一为「更优的排在前面」：
 * {@code compare(a, b) < 0} 表示 a 比 b 更值得下载。
 * 择优时按配置的维度顺序用 thenComparing 串联，因此每个比较器只管自己这一维，
 * 不得夹带其他判断。新增维度只需加一个枚举值与一个比较器，串联逻辑不变。
 * </p>
 *
 * @author Jack
 */
@Slf4j
public enum SortDimension {

    /** 分辨率匹配度，按 resolutionPriority 的先后顺序，不在列表中的排最后 */
    RESOLUTION {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            List<String> priority = criteria.resolutionPriority();
            if (priority.isEmpty()) {
                return NO_PREFERENCE;
            }
            return Comparator.comparingInt(t -> rankOf(t.getParsedResolution(), priority));
        }
    },

    /**
     * 媒介来源匹配度，按 sourcePriority 的先后顺序（典型配置 REMUX,BluRay,WEBDL,HDTV），
     * 不在列表中的排最后。同分辨率下 Remux 与 HDTV 的观感差距远大于做种数差距，
     * 这一维通常应排在 SEEDERS 之前。
     */
    SOURCE {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            List<String> priority = criteria.sourcePriority();
            if (priority.isEmpty()) {
                return NO_PREFERENCE;
            }
            return Comparator.comparingInt(t -> rankOf(t.getParsedSource(), priority));
        }
    },

    /**
     * 发布组匹配度，按 releaseGroupPriority 的先后顺序，不在列表中的排最后。
     * 与发布组黑名单（{@link TorrentBlacklist}）是互补关系：黑名单是"绝不要"，
     * 这一维是"优先要"，不在优先列表里的发布组只是排后面，不会被淘汰。
     */
    RELEASE_GROUP {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            List<String> priority = criteria.releaseGroupPriority();
            if (priority.isEmpty()) {
                return NO_PREFERENCE;
            }
            return Comparator.comparingInt(t -> rankOf(t.getParsedReleaseGroup(), priority));
        }
    },

    /**
     * 下载量计量系数，越小越优——免费(0.0)排最前，同时正确处理 PT 站常见的半价促销(0.5)。
     * 用连续比较而非二值判断，否则 0.5 与 1.0 会被判同级，择优可能随机落到全价种上。
     */
    FREE {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            return Comparator.comparingDouble(TorrentInfo::getDownloadVolumeFactor);
        }
    },

    /**
     * H&R 考核，无考核的站点优先。
     * <p>
     * 与 {@link FilterCriteria#avoidHitAndRun()} 是软硬两手：这一维只是让没有保种义务的候选
     * 排在前面，同等条件下自然避开 H&R；真要一个都不碰才用那个硬开关。
     * H&R 站点常常正是资源质量最好的站点，默认不该把它们直接排除。
     * </p>
     */
    HR {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            return Comparator.comparingInt(t -> t.isHitAndRun() ? 1 : 0);
        }
    },

    /** 做种数，多者优先 */
    SEEDERS {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            return Comparator.comparingInt(TorrentInfo::getSeeders).reversed();
        }
    },

    /**
     * 体积接近偏好值的程度，越接近越优先；未配置偏好值时不参与比较。
     * <p>
     * 取值走 {@link FilterCriteria#effectiveSize}，与硬性过滤的上下限完全同一个口径：
     * 开了「按每集判定」时比的是折算到单集的体积。不这么做的话，同一批候选里
     * 一个单集资源和一个 26 集季包会被放在同一根数轴上比"离偏好体积多远"，
     * 季包必然被判成离谱地远，这一维实际上变成了"排除所有多集包"。
     * </p>
     */
    SIZE {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            long preferred = criteria.preferredSize();
            if (preferred <= 0) {
                // 不能退化成「越小越好」：用户没配偏好体积时那样会总是下到最小的那个
                return NO_PREFERENCE;
            }
            return Comparator.comparingLong(t -> Math.abs(criteria.effectiveSize(t) - preferred));
        }
    };

    /** 恒判同级的比较器，用于「该维度未配置」的情形 */
    private static final Comparator<TorrentInfo> NO_PREFERENCE = (a, b) -> 0;

    /**
     * 该维度的比较器，更优的排在前面。
     */
    public abstract Comparator<TorrentInfo> comparator(FilterCriteria criteria);

    /**
     * 解析逗号分隔的维度名，大小写不敏感。
     * <p>
     * 无法识别的名字只记日志跳过，不抛异常——这份配置是用户手输的，
     * 写错一个词不该让整轮 RSS 轮询挂掉。
     * </p>
     */
    public static List<SortDimension> parseCsv(String csv) {
        List<SortDimension> result = new ArrayList<>();
        for (String name : FilterCriteria.splitCsv(csv)) {
            try {
                result.add(SortDimension.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warn("排序维度配置中存在无法识别的取值，已忽略：{}", name);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 取值在优先级列表中的名次，越小越优。分辨率/来源/发布组三个维度共用：
     * 它们的比较语义完全一致，只是取值来源不同。
     * <p>实现在 {@link PriorityRanker}，与洗版判定共用同一套口径。</p>
     */
    private static int rankOf(String value, List<String> priority) {
        return PriorityRanker.rankOf(value, priority);
    }
}
