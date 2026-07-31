package com.osr.openliststrm.rename.config;

import com.osr.system.domain.SysConfig;
import com.osr.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

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
    void previewRender_合法模板_返回渲染结果() {
        String result = service.previewRender("{{ title }}.{{ extension }}");
        assertTrue(result.contains("."));
    }

    @Test
    void previewRender_模板语法错误_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.previewRender("{{ title "));
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
