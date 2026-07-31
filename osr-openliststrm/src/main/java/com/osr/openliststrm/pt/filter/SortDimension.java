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
     * 下载量计量系数，越小越优——免费(0.0)排最前，同时正确处理 PT 站常见的半价促销(0.5)。
     * 用连续比较而非二值判断，否则 0.5 与 1.0 会被判同级，择优可能随机落到全价种上。
     */
    FREE {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            return Comparator.comparingDouble(TorrentInfo::getDownloadVolumeFactor);
        }
    },

    /** 做种数，多者优先 */
    SEEDERS {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            return Comparator.comparingInt(TorrentInfo::getSeeders).reversed();
        }
    },

    /** 体积接近偏好值的程度，越接近越优先；未配置偏好值时不参与比较 */
    SIZE {
        @Override
        public Comparator<TorrentInfo> comparator(FilterCriteria criteria) {
            long preferred = criteria.preferredSize();
            if (preferred <= 0) {
                // 不能退化成「越小越好」：用户没配偏好体积时那样会总是下到最小的那个
                return NO_PREFERENCE;
            }
            return Comparator.comparingLong(t -> Math.abs(t.getSize() - preferred));
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
     * 分辨率在优先级列表中的名次，越小越优；不在列表中返回列表长度（排最后）。
     * 大小写不敏感——索引器标题里 1080P 与 1080p 都出现过。
     */
    private static int rankOf(String resolution, List<String> priority) {
        if (StringUtils.isBlank(resolution)) {
            return priority.size();
        }
        for (int i = 0; i < priority.size(); i++) {
            if (priority.get(i).equalsIgnoreCase(resolution.trim())) {
                return i;
            }
        }
        return priority.size();
    }
}
