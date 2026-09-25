package com.osr.openliststrm.pt.filter;

import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 草稿当不可信输入：只认白名单字段、枚举值归一到词表、体积 GB 换字节，丢弃的要说出来。
 */
class FilterRuleAiDraftServiceTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void 合规字段换算与归一() {
        FilterRuleAiDraftService.Draft d = FilterRuleAiDraftService.validate(JSONObject.parseObject("{\"changes\":{"
                + "\"maxSize\":30,\"minSize\":\"1.5\",\"freeOnly\":true,\"avoidHitAndRun\":\"0\","
                + "\"resolutionWhitelist\":[\"2160P\",\"1080p\",\"2160p\"],\"excludeKeywords\":\"CAM，TS\"},"
                + "\"explanation\":\"说明\"}"));

        assertEquals(30 * GB, d.changes().get("maxSize"));
        assertEquals(Math.round(1.5 * GB), d.changes().get("minSize"));
        assertEquals("1", d.changes().get("freeOnly"));
        assertEquals("0", d.changes().get("avoidHitAndRun"));
        assertEquals("2160p,1080p", d.changes().get("resolutionWhitelist"));
        assertEquals("CAM,TS", d.changes().get("excludeKeywords"));
        assertEquals("说明", d.explanation());
        assertTrue(d.dropped().isEmpty());
    }

    @Test
    void 词表外的值与未知字段丢掉并说明() {
        FilterRuleAiDraftService.Draft d = FilterRuleAiDraftService.validate(JSONObject.parseObject("{\"changes\":{"
                + "\"excludeTags\":[\"Dolby Vision\",\"杜比全景声\"],\"sourceWhitelist\":[\"蓝光原盘\"],"
                + "\"sortPriority\":\"SIZE\",\"maxSize\":-1,\"freeOnly\":\"maybe\"}}"));

        assertEquals("Dolby Vision", d.changes().get("excludeTags"), "部分合规时保留合规的");
        assertFalse(d.changes().containsKey("sourceWhitelist"), "全部不合规时整字段不改");
        assertFalse(d.changes().containsKey("sortPriority"));
        assertFalse(d.changes().containsKey("maxSize"));
        assertFalse(d.changes().containsKey("freeOnly"));
        assertEquals(5, d.dropped().size(), d.dropped().toString());
    }

    @Test
    void 提示词带上当前规则与词表() {
        PtFilterConfigPlus current = new PtFilterConfigPlus();
        current.setMaxSize(20 * GB);
        String prompt = FilterRuleAiDraftService.buildPrompt("不要 DTS", current);

        assertTrue(prompt.contains("\"maxSize\":20.0"), prompt);
        assertTrue(prompt.contains("Dolby Vision"));
        assertTrue(prompt.contains("不要 DTS"));
    }
}
