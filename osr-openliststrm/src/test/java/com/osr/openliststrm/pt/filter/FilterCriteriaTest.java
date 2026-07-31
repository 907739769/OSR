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
        FilterCriteria criteria = new FilterCriteria(
                1, 0L, 0L, false, List.of(), List.of(), mutable, List.of(), List.of(SortDimension.SEEDERS), 0L, false);

        mutable.add("720p");

        assertEquals(List.of("1080p"), criteria.resolutionPriority());
    }

    @Test
    void 列表字段不可变_尝试修改抛异常() {
        FilterCriteria criteria = new FilterCriteria(
                1, 0L, 0L, false, List.of(), List.of(), List.of("1080p"), List.of(), List.of(SortDimension.SEEDERS), 0L, false);

        assertThrows(UnsupportedOperationException.class, () -> criteria.resolutionPriority().add("720p"));
    }

    @Test
    void 排序维度为空_回退到内置默认顺序() {
        FilterCriteria criteria = new FilterCriteria(
                1, 0L, 0L, false, List.of(), List.of(), List.of(), List.of(), List.of(), 0L, false);

        // 空的排序配置会让择优退化成"随便挑一个"，必须有兜底
        assertEquals(FilterCriteria.DEFAULT_SORT_PRIORITY, criteria.sortPriority());
    }

    @Test
    void 各列表分量传null_归一为空列表而非NPE() {
        // 后续阶段会直接 new 这个 public record，不能指望调用方永远传非 null；
        // null 应归一为空列表，与"空 -> 不限/回退默认"的既有语义保持一致
        FilterCriteria criteria = assertDoesNotThrow(() -> new FilterCriteria(
                1, 0L, 0L, false, null, null, null, null, null, 0L, false));

        assertTrue(criteria.includeKeywords().isEmpty());
        assertTrue(criteria.excludeKeywords().isEmpty());
        assertTrue(criteria.resolutionPriority().isEmpty());
        assertTrue(criteria.resolutionWhitelist().isEmpty());
        assertEquals(FilterCriteria.DEFAULT_SORT_PRIORITY, criteria.sortPriority());
    }
}
