package com.osr.openliststrm.pt.upgrade;

import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.PriorityRanker;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 洗版比较的维度。取值写入 {@code pt_upgrade_config.quality_priority}，逗号分隔。
 * <p>
 * 每个维度给出一个名次，<b>越小越优</b>。判定"新版本是否严格优于旧版本"时按配置的
 * 维度顺序做字典序比较：第一个名次不同的维度说了算。
 * </p>
 * <p>
 * <b>这里刻意只收录画质维度，绝不能加入 SEEDERS / SIZE / FREE。</b>
 * 那些取值随时间连续变化：同分辨率但做种更多的种子会被判成"更优"，下载完之后
 * 下一轮又冒出别的做种更多的种子，于是无限洗版。而本枚举里的四个维度取值都来自
 * 有限集合（优先级列表长度、目标标签个数），字典序比较在有限集合上构成全预序——
 * 数学上不可能出现 A 优于 B 且 B 优于 A 的环，这正是"不会来回洗"的唯一保证。
 * </p>
 *
 * @author Jack
 */
@Slf4j
public enum UpgradeDimension {

    /** 分辨率，按 pt_filter_config.resolution_priority 的先后顺序 */
    RESOLUTION {
        @Override
        public int rank(QualityProfile profile, UpgradeCriteria criteria) {
            return PriorityRanker.rankOf(profile.resolution(), criteria.resolutionPriority());
        }
    },

    /** 媒介来源，按 pt_filter_config.source_priority 的先后顺序 */
    SOURCE {
        @Override
        public int rank(QualityProfile profile, UpgradeCriteria criteria) {
            return PriorityRanker.rankOf(profile.source(), criteria.sourcePriority());
        }
    },

    /**
     * 目标质量标签的欠缺个数，越少越优。
     * <p>
     * 用"还差几个目标标签"而不是"有几个标签"：后者会让一个塞满无关标签的种子
     * 显得更优。未配置目标标签时所有画像都返回 0，该维度自然失效。
     * </p>
     */
    TAG {
        @Override
        public int rank(QualityProfile profile, UpgradeCriteria criteria) {
            int missing = 0;
            for (String target : criteria.targetTags()) {
                if (!profile.hasTag(target)) {
                    missing++;
                }
            }
            return missing;
        }
    },

    /** 发布组，按 pt_filter_config.release_group_priority 的先后顺序 */
    RELEASE_GROUP {
        @Override
        public int rank(QualityProfile profile, UpgradeCriteria criteria) {
            return PriorityRanker.rankOf(profile.releaseGroup(), criteria.releaseGroupPriority());
        }
    };

    /** 该维度下这份画像的名次，越小越优 */
    public abstract int rank(QualityProfile profile, UpgradeCriteria criteria);

    /**
     * 解析逗号分隔的维度名，大小写不敏感。
     * <p>
     * 无法识别的名字只记日志跳过，不抛异常——这份配置是用户手输的，写错一个词不该让
     * 整轮洗版扫描挂掉。与 {@code SortDimension#parseCsv} 同样的容错取向。
     * </p>
     */
    public static List<UpgradeDimension> parseCsv(String csv) {
        List<UpgradeDimension> result = new ArrayList<>();
        for (String name : FilterCriteria.splitCsv(csv)) {
            try {
                result.add(UpgradeDimension.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warn("洗版维度配置中存在无法识别的取值，已忽略：{}", name);
            }
        }
        return List.copyOf(result);
    }
}
