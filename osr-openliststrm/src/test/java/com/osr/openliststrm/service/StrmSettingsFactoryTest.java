package com.osr.openliststrm.service;

import com.osr.openliststrm.config.OpenlistConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 全局配置与任务级覆盖的合并。核心约定只有一条：<b>只有出现在 JSON 里的键才覆盖</b>，
 * 因此覆盖为空时必须与引入该字段之前的行为逐字段一致。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrmSettingsFactoryTest {

    private static final long MB = 1024 * 1024;

    @Mock
    private OpenlistConfig global;

    @BeforeEach
    void setUp() {
        when(global.getOpenListStrmOutputDir()).thenReturn("/data/strm");
        when(global.getOpenListStrmDownloadSub()).thenReturn("1");
        when(global.getMinFileSizeBytes()).thenReturn(100 * MB);
    }

    @Test
    void 覆盖为空时全部沿用全局配置() {
        for (String empty : new String[] { null, "", "   " }) {
            StrmSettings s = StrmSettingsFactory.build(global, empty);
            assertEquals("/data/strm", s.outputDir(), "输入=" + empty);
            assertTrue(s.downloadSub(), "输入=" + empty);
            assertEquals(100 * MB, s.minSize(), "输入=" + empty);
        }
    }

    @Test
    void 只覆盖出现的键_其余保持全局() {
        StrmSettings s = StrmSettingsFactory.build(global, "{\"downloadSub\":\"0\"}");

        assertFalse(s.downloadSub(), "出现的键应生效");
        assertEquals("/data/strm", s.outputDir(), "未出现的键应保持全局");
        assertEquals(100 * MB, s.minSize(), "未出现的键应保持全局");
    }

    @Test
    void 覆盖输出目录并去掉首尾空白() {
        StrmSettings s = StrmSettingsFactory.build(global, "{\"outputDir\":\"  /data/strm-anime  \"}");
        assertEquals("/data/strm-anime", s.outputDir());
    }

    @Test
    void 输出目录被覆盖成空串时退回默认根目录() {
        // 空的输出根目录会让 STRM 直接写进程工作目录，比忽略这次覆盖危险得多
        StrmSettings s = StrmSettingsFactory.build(global, "{\"outputDir\":\"\"}");
        assertEquals(StrmSettingsFactory.DEFAULT_OUTPUT_DIR, s.outputDir());
    }

    @Test
    void 全局输出目录未配置时也退回默认根目录() {
        when(global.getOpenListStrmOutputDir()).thenReturn(null);
        assertEquals(StrmSettingsFactory.DEFAULT_OUTPUT_DIR, StrmSettingsFactory.build(global, null).outputDir());
    }

    @Test
    void 最小体积按MB填写换算成字节() {
        assertEquals(500 * MB, StrmSettingsFactory.build(global, "{\"minFileSize\":500}").minSize());
    }

    @Test
    void 最小体积填0或负数一律当作不限() {
        assertEquals(0L, StrmSettingsFactory.build(global, "{\"minFileSize\":0}").minSize());
        assertEquals(0L, StrmSettingsFactory.build(global, "{\"minFileSize\":-5}").minSize());
    }

    @Test
    void 最小体积填成带单位的字符串时回退全局() {
        assertEquals(100 * MB, StrmSettingsFactory.build(global, "{\"minFileSize\":\"500MB\"}").minSize());
    }

    @Test
    void 开关同时认字符串1和原生布尔true() {
        when(global.getOpenListStrmDownloadSub()).thenReturn("0");

        assertTrue(StrmSettingsFactory.build(global, "{\"downloadSub\":\"1\"}").downloadSub());
        // 前端表单很容易提交原生布尔值，不认的话「下字幕」会被静默关掉
        assertTrue(StrmSettingsFactory.build(global, "{\"downloadSub\":true}").downloadSub());
        assertFalse(StrmSettingsFactory.build(global, "{\"downloadSub\":false}").downloadSub());
    }

    @Test
    void 覆盖不是合法JSON时整体退回全局而不是抛异常() {
        for (String broken : new String[] { "{不是json", "[1,2,3]", "\"just a string\"", "null" }) {
            StrmSettings s = StrmSettingsFactory.build(global, broken);
            assertEquals("/data/strm", s.outputDir(), "输入=" + broken);
            assertTrue(s.downloadSub(), "输入=" + broken);
            assertEquals(100 * MB, s.minSize(), "输入=" + broken);
        }
    }
}
