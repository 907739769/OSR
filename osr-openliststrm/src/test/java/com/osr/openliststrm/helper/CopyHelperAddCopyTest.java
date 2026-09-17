package com.osr.openliststrm.helper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 单条复制记录的 upsert：必须同步写完并回填 id（紧接着启动的监控靠它 updateById），
 * 且查已有记录要 LIMIT 1、不因重复行抛异常。
 */
class CopyHelperAddCopyTest {

    @Mock
    private IOpenlistCopyPlusService openlistCopyPlusService;

    @InjectMocks
    private CopyHelper helper;

    @BeforeAll
    static void registerTableInfo() {
        // 检查 wrapper 生成的 SQL 要解析实体 lambda，那份缓存平时由 Mapper 注册时建立，纯单测里手动建一次
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(OpenlistCopyPlus.class.getName());
        TableInfoHelper.initTableInfo(assistant, OpenlistCopyPlus.class);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private static OpenlistCopyPlus copy() {
        OpenlistCopyPlus copy = new OpenlistCopyPlus();
        copy.setCopySrcPath("/src");
        copy.setCopySrcFileName("a.mkv");
        copy.setCopyStatus("1");
        return copy;
    }

    @Test
    void 已有记录_返回前就回填id并按id更新() {
        OpenlistCopyPlus existing = new OpenlistCopyPlus();
        existing.setCopyId(42);
        when(openlistCopyPlusService.getOne(any(Wrapper.class), eq(false))).thenReturn(existing);
        OpenlistCopyPlus copy = copy();

        helper.addCopy(copy);

        // 同步语义：方法返回时 id 已在对象上，不依赖任何异步调度
        assertEquals(42, copy.getCopyId());
        verify(openlistCopyPlusService).updateById(copy);
        verify(openlistCopyPlusService, never()).save(any());
    }

    @Test
    void 没有记录_新增() {
        when(openlistCopyPlusService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
        OpenlistCopyPlus copy = copy();

        helper.addCopy(copy);

        verify(openlistCopyPlusService).save(copy);
    }

    @Test
    void 查询已有记录带LIMIT1且重复行不抛异常() {
        when(openlistCopyPlusService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);

        helper.addCopy(copy());

        ArgumentCaptor<Wrapper> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(openlistCopyPlusService).getOne(wrapper.capture(), eq(false));
        assertTrue(wrapper.getValue().getCustomSqlSegment().contains("LIMIT 1"), wrapper.getValue().getCustomSqlSegment());
    }
}
