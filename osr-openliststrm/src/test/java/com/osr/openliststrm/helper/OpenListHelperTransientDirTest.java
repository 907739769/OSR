package com.osr.openliststrm.helper;

import com.osr.openliststrm.config.OpenlistConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Transmission 删种临时目录识别。判据只能是目录名，所以这里守的是"哪些名字算临时目录"这一条线本身。
 * 默认规则对齐 Transmission 的 mkdtemp 模板 {@code <种子名>__XXXXXX}：两个下划线 + 恰好 6 位
 * {@code [A-Za-z0-9]}，位数和字符集都不能松。
 *
 * @author Jack
 */
class OpenListHelperTransientDirTest {

    private static final String DEFAULT_RULE = ".+__[0-9A-Za-z]{6}";

    /** 用户实际撞到的那个目录名 */
    private static final String REAL_CASE =
            "Star.Wars.Visions.Presents.The.Ninth.Jedi.2026.S01.2160p.DSNP.WEB-DL.DV.HDR.H.265.DDP5.1-ADWeb__kefDJG";

    @Mock
    private OpenlistConfig config;

    private OpenListHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        helper = new OpenListHelper();
        inject(helper, "config", config);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void 默认规则_命中下载器删种产生的临时目录() {
        when(config.getCopyTransientDirPatterns()).thenReturn(DEFAULT_RULE);

        assertTrue(helper.isTransientDir(REAL_CASE));
        assertTrue(helper.isTransientDir("Some.Show.S01-GROUP__aB3xY9"));
    }

    @Test
    void 默认规则_不误伤正常剧集目录() {
        when(config.getCopyTransientDirPatterns()).thenReturn(DEFAULT_RULE);

        // 临时后缀被剥掉后就是真目录名，绝不能跟着一起跳过
        assertFalse(helper.isTransientDir(
                "Star.Wars.Visions.Presents.The.Ninth.Jedi.2026.S01.2160p.DSNP.WEB-DL.DV.HDR.H.265.DDP5.1-ADWeb"));
        assertFalse(helper.isTransientDir("2026"));
        assertFalse(helper.isTransientDir("剧集"));
        // 单下划线、位数不对、非结尾，都不是那个形态
        assertFalse(helper.isTransientDir("Show_kefDJG"));
        assertFalse(helper.isTransientDir("Show__kefDJ"));
        assertFalse(helper.isTransientDir("Show__kefDJGH"));
        assertFalse(helper.isTransientDir("Show__kefDJG.Extra"));
    }

    @Test
    void 空名字与null不匹配() {
        when(config.getCopyTransientDirPatterns()).thenReturn(DEFAULT_RULE);

        assertFalse(helper.isTransientDir(null));
        assertFalse(helper.isTransientDir(""));
    }

    @Test
    void 配置为off时整个过滤关闭() {
        when(config.getCopyTransientDirPatterns()).thenReturn("off");

        assertFalse(helper.isTransientDir(REAL_CASE));
    }

    @Test
    void 多条规则逗号分隔_任一命中即算临时目录() {
        when(config.getCopyTransientDirPatterns()).thenReturn(DEFAULT_RULE + " , \\.tmp\\..+");

        assertTrue(helper.isTransientDir(REAL_CASE));
        assertTrue(helper.isTransientDir(".tmp.whatever"));
        assertFalse(helper.isTransientDir("Normal.Show.S01"));
    }

    @Test
    void 单条规则写错不让整份规则失效() {
        when(config.getCopyTransientDirPatterns()).thenReturn("[unclosed," + DEFAULT_RULE);

        assertTrue(helper.isTransientDir(REAL_CASE));
    }

    @Test
    void 配置改动后立刻生效_缓存按配置原文失效() {
        when(config.getCopyTransientDirPatterns()).thenReturn(DEFAULT_RULE);
        assertTrue(helper.isTransientDir(REAL_CASE));

        when(config.getCopyTransientDirPatterns()).thenReturn("off");
        assertFalse(helper.isTransientDir(REAL_CASE));
    }
}
