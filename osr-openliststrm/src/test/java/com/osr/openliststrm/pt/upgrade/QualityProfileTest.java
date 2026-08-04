package com.osr.openliststrm.pt.upgrade;

import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityProfileTest {

    @Test
    void 序列化后再反序列化_内容不变() {
        QualityProfile origin = new QualityProfile("2160p", "REMUX", "CHDBits", List.of("HDR10", "ATMOS"));

        assertEquals(origin, QualityProfile.fromJson(origin.toJson()));
    }

    @Test
    void 从种子构造_取本地解析结果() {
        TorrentInfo t = new TorrentInfo();
        t.setParsedResolution("1080p");
        t.setParsedSource("WEBDL");
        t.setParsedReleaseGroup("FRDS");
        t.setParsedTags(new ArrayList<>(List.of("HDR10")));

        QualityProfile p = QualityProfile.from(t);

        assertEquals("1080p", p.resolution());
        assertEquals("WEBDL", p.source());
        assertEquals("FRDS", p.releaseGroup());
        assertEquals(List.of("HDR10"), p.tags());
    }

    @Test
    void 空白取值归一为null_避免空串与null两种无值形态() {
        QualityProfile p = new QualityProfile("  ", "", null, null);

        assertNull(p.resolution());
        assertNull(p.source());
        assertNull(p.releaseGroup());
        assertTrue(p.tags().isEmpty());
    }

    @Test
    void 标签列表不可变() {
        QualityProfile p = new QualityProfile("1080p", "WEBDL", null, List.of("HDR10"));

        assertThrows(UnsupportedOperationException.class, () -> p.tags().add("ATMOS"));
    }

    @Test
    void 标签列表被防御性拷贝() {
        List<String> mutable = new ArrayList<>(List.of("HDR10"));
        QualityProfile p = new QualityProfile("1080p", "WEBDL", null, mutable);

        mutable.add("ATMOS");

        assertEquals(List.of("HDR10"), p.tags());
    }

    @Test
    void 反序列化脏数据_返回null按无基线处理() {
        // 一条脏数据不该让整轮扫描挂掉，更不该被当成"基线极差"而触发盲目升级
        assertNull(QualityProfile.fromJson(null));
        assertNull(QualityProfile.fromJson(""));
        assertNull(QualityProfile.fromJson("   "));
        assertNull(QualityProfile.fromJson("not json at all"));
        assertNull(QualityProfile.fromJson("[1,2,3]"));
    }

    @Test
    void 反序列化缺字段的JSON_缺的部分归一为无值() {
        QualityProfile p = QualityProfile.fromJson("{\"resolution\":\"1080p\"}");

        assertEquals("1080p", p.resolution());
        assertNull(p.source());
        assertTrue(p.tags().isEmpty());
    }

    @Test
    void 标签命中判定_整词且大小写不敏感() {
        QualityProfile p = new QualityProfile("2160p", "REMUX", null, List.of("HDR10", "Atmos"));

        assertTrue(p.hasTag("hdr10"));
        assertTrue(p.hasTag("ATMOS"));
        // 整词相等：HDR 不该命中 HDR10
        assertFalse(p.hasTag("HDR"));
    }

    @Test
    void 展示摘要_省略解析不出的项() {
        assertEquals("2160p / REMUX / HDR10 / CHDBits",
                new QualityProfile("2160p", "REMUX", "CHDBits", List.of("HDR10")).describe());
        assertEquals("1080p", new QualityProfile("1080p", null, null, List.of()).describe());
        assertEquals("未知分辨率", new QualityProfile(null, null, null, List.of()).describe());
    }
}
