package com.osr.openliststrm.backup;

import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCodecTest {

    private final EntityCodec<PtSubscriptionPlus> codec = new EntityCodec<>(PtSubscriptionPlus.class);

    /** 列表页辅助字段（exist = false）与 BaseEntity 的审计字段都不是这张表的列，不能进备份 */
    @Test
    void 只包含实体自己声明的库表列() {
        assertTrue(codec.properties().contains("tmdbId"));
        assertFalse(codec.properties().contains("sortBy"), "exist=false 的字段不该进来");
        assertFalse(codec.properties().contains("inLibraryCount"));
        assertFalse(codec.properties().contains("createTime"), "BaseEntity 的字段不该进来");
        assertFalse(codec.properties().contains("id"), "主键单独处理");
        assertEquals("id", codec.idColumn());
        assertEquals("tmdb_id", codec.column("tmdbId"));
    }

    /** 值为 null 的列也要保留：恢复时靠它区分「备份里清空了」与「备份里没提」 */
    @Test
    void 转JSON保留null列_往返一致() {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setTmdbId("100");
        sub.setSeason(2);

        JSONObject json = codec.toJson(sub);

        assertTrue(json.containsKey("filterOverride"));
        PtSubscriptionPlus back = codec.fromJson(json);
        assertEquals("100", back.getTmdbId());
        assertEquals(2, back.getSeason());
    }

    @Test
    void BigDecimal按数值比较() {
        assertTrue(EntityCodec.sameValue(new BigDecimal("1.5"), new BigDecimal("1.50")));
        assertFalse(EntityCodec.sameValue(new BigDecimal("1.5"), new BigDecimal("2")));
    }
}
