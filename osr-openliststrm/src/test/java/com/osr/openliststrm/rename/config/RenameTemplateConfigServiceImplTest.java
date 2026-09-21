package com.osr.openliststrm.rename.config;

import com.osr.system.domain.SysConfig;
import com.osr.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.osr.openliststrm.rename.render.PebbleRenderer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RenameTemplateConfigServiceImplTest {

    @Mock
    private ISysConfigService sysConfigService;

    private RenameTemplateConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RenameTemplateConfigServiceImpl();
        service.sysConfigService = sysConfigService;
    }

    @Test
    void getTemplate_数据库中有配置_返回配置值() {
        when(sysConfigService.selectConfigByKey(IRenameTemplateConfigService.CONFIG_KEY))
                .thenReturn("{{ title }}.{{ extension }}");
        assertEquals("{{ title }}.{{ extension }}", service.getTemplate());
    }

    @Test
    void getTemplate_数据库中没有配置_返回内置默认模板() {
        when(sysConfigService.selectConfigByKey(IRenameTemplateConfigService.CONFIG_KEY)).thenReturn("");
        assertEquals(IRenameTemplateConfigService.DEFAULT_TEMPLATE, service.getTemplate());
    }

    @Test
    void previewSamples_合法模板_电影与剧集各渲染一份() {
        Map<String, String> result = service.previewSamples("{{ title }}.{{ extension }}");
        assertEquals("示例电影.mkv", result.get(IRenameTemplateConfigService.SAMPLE_MOVIE));
        assertEquals("示例剧集.mkv", result.get(IRenameTemplateConfigService.SAMPLE_TV));
    }

    /**
     * 模板里全是 {% if season %} 分支：只有一份带季集的样例时，电影那半边永远预览不到。
     * 用默认模板钉住两份样例确实走到了不同分支。
     */
    @Test
    void previewSamples_默认模板_电影样例不出现季集剧集样例出现() {
        Map<String, String> result = service.previewSamples(IRenameTemplateConfigService.DEFAULT_TEMPLATE);
        String movie = result.get(IRenameTemplateConfigService.SAMPLE_MOVIE);
        String tv = result.get(IRenameTemplateConfigService.SAMPLE_TV);
        assertFalse(movie.contains("Season"), movie);
        assertFalse(movie.contains("S1E"), movie);
        assertTrue(tv.contains("Season 1/"), tv);
        assertTrue(tv.contains("S1E3"), tv);
    }

    @Test
    void previewSamples_模板语法错误_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.previewSamples("{{ title "));
    }

    /** 清单由渲染上下文生成：说明表漏登记也不会缺变量，metadata 这种 26KB 的原始响应不进清单 */
    @Test
    void templateVariables_来自渲染上下文_不含metadata() {
        List<TemplateVariable> vars = service.templateVariables();
        Set<String> names = vars.stream().map(TemplateVariable::name).collect(Collectors.toSet());

        Set<String> expected = new HashSet<>(PebbleRenderer.contextOf(RenameTemplateConfigServiceImpl.buildTvSample()).keySet());
        expected.remove("metadata");
        assertEquals(expected, names);
        assertTrue(names.containsAll(Set.of("title", "season", "episodeName", "releaseGroup", "extension")));

        TemplateVariable tags = vars.stream().filter(v -> v.name().equals("tags")).findFirst().orElseThrow();
        assertEquals("HDR", tags.sample());
        assertTrue(tags.common());
        assertNotNull(tags.label());
    }

    /**
     * MediaInfo 新加字段时它会自动出现在清单里，但没有说明的话页面上只剩一个英文名。
     * 这条用例在那时提醒去 VARIABLE_LABELS 补一条；说明表的 key 拼错也会在这里暴露（对应变量缺说明）。
     */
    @Test
    void templateVariables_每个变量都有中文说明() {
        for (TemplateVariable v : service.templateVariables()) {
            assertNotNull(v.label(), "变量 " + v.name() + " 缺少中文说明（RenameTemplateConfigServiceImpl.VARIABLE_LABELS）");
        }
    }

    @Test
    void saveTemplate_模板非法_不写入配置直接抛异常() {
        assertThrows(IllegalArgumentException.class, () -> service.saveTemplate("{{ title "));
        verify(sysConfigService, never()).insertConfig(any());
        verify(sysConfigService, never()).updateConfig(any());
    }

    @Test
    void saveTemplate_配置不存在_新增配置并刷新缓存() {
        when(sysConfigService.selectConfigList(any())).thenReturn(Collections.emptyList());
        service.saveTemplate("{{ title }}.{{ extension }}");
        verify(sysConfigService).insertConfig(any());
        verify(sysConfigService).resetConfigCache();
    }

    @Test
    void saveTemplate_配置已存在_更新配置并刷新缓存() {
        SysConfig existing = new SysConfig();
        existing.setConfigId(100L);
        existing.setConfigKey(IRenameTemplateConfigService.CONFIG_KEY);
        when(sysConfigService.selectConfigList(any())).thenReturn(List.of(existing));
        service.saveTemplate("{{ title }}.{{ extension }}");
        verify(sysConfigService).updateConfig(existing);
        verify(sysConfigService).resetConfigCache();
    }
}
