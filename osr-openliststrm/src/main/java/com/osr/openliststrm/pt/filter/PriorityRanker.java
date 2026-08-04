package com.osr.openliststrm.pt.filter;

import com.osr.common.utils.StringUtils;

import java.util.List;

/**
 * 「取值在优先级列表中排第几」的共享判定。择优排序（{@link SortDimension}）与洗版判定
 * （{@code com.osr.openliststrm.pt.upgrade.UpgradeDimension}）必须用同一套口径——
 * 否则会出现「按排序规则 A 比 B 好，按洗版规则又不是」这种自相矛盾。
 *
 * @author Jack
 */
public final class PriorityRanker {

    private PriorityRanker() {
    }

    /**
     * 名次，越小越优；解析不出（空）或不在列表中一律返回列表长度（并列排最后）。
     * 大小写不敏感——索引器标题里 1080P 与 1080p、WEB-DL 与 web-dl 都出现过。
     * <p>
     * 「不在列表中」与「解析不出」故意判成同一名次：两者都表示"没有已知偏好"，
     * 区别对待会凭空造出一个并不存在的优劣关系。
     * </p>
     */
    public static int rankOf(String value, List<String> priority) {
        if (priority == null || priority.isEmpty()) {
            return 0;
        }
        if (StringUtils.isBlank(value)) {
            return priority.size();
        }
        String trimmed = value.trim();
        for (int i = 0; i < priority.size(); i++) {
            if (priority.get(i).equalsIgnoreCase(trimmed)) {
                return i;
            }
        }
        return priority.size();
    }
}
