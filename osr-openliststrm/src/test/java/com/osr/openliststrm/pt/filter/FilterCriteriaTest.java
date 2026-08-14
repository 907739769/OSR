package com.osr.openliststrm.pt.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterCriteriaTest {

    @Test
    void splitCsv_正常逗号分隔_逐项去空白() {
        assertEquals(List.of("2160p", "1080p", "720p"), FilterCriteria.splitCsv("2160p, 1080p ,720p"));
    }

    @Test
    void splitCsv_空值_返回空列表() {
        assertTrue(FilterCriteria.splitCsv(null).isEmpty());
        assertTrue(FilterCriteria.splitCsv("").isEmpty());
        assertTrue(FilterCriteria.splitCsv("   ").isEmpty());
    }

    @Test
    void splitCsv_含空项_空项被丢弃() {
        assertEquals(List.of("a", "b"), FilterCriteria.splitCsv("a,,b,"));
    }

    @Test
    void splitCsv_中文关键词_正常切分() {
        assertEquals(List.of("预告", "花絮"), FilterCriteria.splitCsv("预告,花絮"));
    }

    @Test
    void 列表字段被防御性拷贝_外部修改不影响已构造的条件() {
        List<String> mutable = new java.util.ArrayList<>(List.of("1080p"));
        FilterCriteria criteria = FilterCriteria.builder()
                .minSeeders(1)
                .resolutionPriority(mutable)
                .sortPriority(List.of(SortDimension.SEEDERS))
                .build();

        mutable.add("720p");

        assertEquals(List.of("1080p"), criteria.resolutionPriority());
    }

    @Test
    void 列表字段不可变_尝试修改抛异常() {
        FilterCriteria criteria = FilterCriteria.builder()
                .minSeeders(1)
                .resolutionPriority(List.of("1080p"))
                .sortPriority(List.of(SortDimension.SEEDERS))
                .build();

        assertThrows(UnsupportedOperationException.class, () -> criteria.resolutionPriority().add("720p"));
    }

    @Test
    void 排序维度为空_回退到内置默认顺序() {
        FilterCriteria criteria = FilterCriteria.builder().minSeeders(1).sortPriority(List.of()).build();

        // 空的排序配置会让择优退化成"随便挑一个"，必须有兜底
        assertEquals(FilterCriteria.DEFAULT_SORT_PRIORITY, criteria.sortPriority());
    }

    @Test
    void 各列表分量传null_归一为空列表而非NPE() {
        // 位置参数版本仍然是 record 的规范构造器，builder 未设置的分量也恰好走这条路径传 null；
        // null 应归一为空列表，与"空 -> 不限/回退默认"的既有语义保持一致
        FilterCriteria criteria = assertDoesNotThrow(() -> new FilterCriteria(
                1, 0L, 0L, false, null, null, null, null, null, null, null, null, null, null, null, 0L, false, false,
                false));

        assertTrue(criteria.includeKeywords().isEmpty());
        assertTrue(criteria.excludeKeywords().isEmpty());
        assertTrue(criteria.descriptionExcludeKeywords().isEmpty());
        assertTrue(criteria.resolutionPriority().isEmpty());
        assertTrue(criteria.resolutionWhitelist().isEmpty());
        assertTrue(criteria.sourceWhitelist().isEmpty());
        assertTrue(criteria.sourcePriority().isEmpty());
        assertTrue(criteria.requiredTags().isEmpty());
        assertTrue(criteria.excludeTags().isEmpty());
        assertTrue(criteria.releaseGroupPriority().isEmpty());
        assertEquals(FilterCriteria.DEFAULT_SORT_PRIORITY, criteria.sortPriority());
    }

    @Test
    void builder未设置的分量_全部归一为安全默认值() {
        // 新增维度后既有调用方不补参数也不该炸：列表分量空 = 不限，开关分量 false = 不启用
        FilterCriteria criteria = FilterCriteria.builder().build();

        assertEquals(0, criteria.minSeeders());
        assertTrue(criteria.descriptionExcludeKeywords().isEmpty());
        assertTrue(criteria.sourceWhitelist().isEmpty());
        assertTrue(criteria.requiredTags().isEmpty());
        assertTrue(criteria.excludeTags().isEmpty());
        assertTrue(criteria.releaseGroupPriority().isEmpty());
        assertEquals(FilterCriteria.DEFAULT_SORT_PRIORITY, criteria.sortPriority());
    }
}
