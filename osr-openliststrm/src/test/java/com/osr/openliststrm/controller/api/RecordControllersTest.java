package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.helper.MediaExtensionProvider;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.mapper.OpenlistCopyPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 三个记录页控制器的共同约束：系统生成的记录不开放继承来的写接口；统计条按状态分组且不带排序；
 * STRM 记录按文件类型筛选的 SQL 形态。
 */
class RecordControllersTest {

    private static void inject(Object target, String field, Object value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                type.getDeclaredField(field);
                ReflectionTestUtils.setField(target, type, field, value, null);
                return;
            } catch (NoSuchFieldException ignored) {
                // 往父类找
            }
        }
        throw new IllegalArgumentException(field);
    }

    @Test
    void 同步记录与重命名明细_继承来的新增修改单条删除一律拒绝且不碰数据() {
        IOpenlistCopyPlusService copyService = mock(IOpenlistCopyPlusService.class);
        OpenlistCopyRestController copy = new OpenlistCopyRestController();
        inject(copy, "service", copyService);

        IRenameDetailPlusService renameService = mock(IRenameDetailPlusService.class);
        RenameDetailRestController rename = new RenameDetailRestController();
        inject(rename, "service", renameService);

        for (Result<Void> result : List.of(
                copy.add(new OpenlistCopyPlus()), copy.edit(new OpenlistCopyPlus()), copy.delete(1),
                rename.add(new RenameDetailPlus()), rename.edit(new RenameDetailPlus()), rename.delete(1))) {
            assertNotEquals(200, result.getCode());
            assertTrue(result.getMessage().contains("由系统生成"), result.getMessage());
        }
        verifyNoInteractions(copyService, renameService);
    }

    @Test
    void 统计条_按状态分组计数_筛选条件保留但不带状态和排序() {
        OpenlistCopyPlusMapper mapper = mock(OpenlistCopyPlusMapper.class);
        IOpenlistCopyPlusService copyService = mock(IOpenlistCopyPlusService.class);
        when(copyService.getBaseMapper()).thenReturn(mapper);
        when(mapper.selectMaps(any())).thenReturn(List.of(
                Map.of("status", "2", "cnt", 17L),
                Map.of("status", "3", "cnt", 1182L),
                new java.util.HashMap<>(Map.of("cnt", 4L))));  // status 为 NULL 的历史行
        OpenlistCopyRestController controller = new OpenlistCopyRestController();
        inject(controller, "service", copyService);

        OpenlistCopyPlus query = new OpenlistCopyPlus();
        query.setCopySrcPath("/剧集");
        query.setCopyStatus("2");
        Map<String, Long> counts = controller.stats(query).getData();

        assertEquals(17L, counts.get("2"));
        assertEquals(1182L, counts.get("3"));
        assertEquals(1203L, counts.get("total"));
        ArgumentCaptor<Wrapper> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectMaps(wrapper.capture());
        String sql = ((QueryWrapper<?>) wrapper.getValue()).getCustomSqlSegment();
        assertTrue(sql.contains("copy_src_path LIKE"), sql);
        assertTrue(sql.contains("GROUP BY copy_status"), sql);
        // 带着状态条件，统计条上除了被筛的那个状态全是 0；带着 ORDER BY 则在 ONLY_FULL_GROUP_BY 下直接报错
        assertFalse(sql.contains("copy_status ="), sql);
        assertFalse(sql.toUpperCase().contains("ORDER BY"), sql);
    }

    @Test
    void 列表仍按创建时间倒序() {
        OpenlistCopyRestController controller = new OpenlistCopyRestController();
        String sql = controller.buildQueryWrapper(new OpenlistCopyPlus()).getCustomSqlSegment();
        assertTrue(sql.contains("ORDER BY create_time DESC"), sql);
    }

    @Test
    void STRM记录按文件类型筛选_按扩展名后缀OR匹配() {
        OpenlistStrmRestController controller = strmControllerWith(new LinkedHashSet<>(List.of("mkv", "mp4")), Set.of("srt"));
        QueryWrapper<OpenlistStrmPlus> wrapper = new QueryWrapper<>();

        controller.applyConditions(wrapper, new OpenlistStrmPlus(), "video");

        String sql = wrapper.getCustomSqlSegment();
        assertTrue(sql.matches("(?s).*\\(strm_file_name LIKE .* OR strm_file_name LIKE .*\\).*"), sql);
        assertFalse(sql.contains("( OR"), sql);
        assertEquals(Set.of("%.mkv", "%.mp4"), Set.copyOf(wrapper.getParamNameValuePairs().values()));
    }

    @Test
    void STRM记录按文件类型筛选_扩展名配置为空时一条都不匹配而不是退化成全部() {
        OpenlistStrmRestController controller = strmControllerWith(Set.of("mkv"), Set.of());
        QueryWrapper<OpenlistStrmPlus> wrapper = new QueryWrapper<>();

        controller.applyConditions(wrapper, null, "subtitle");

        assertTrue(wrapper.getCustomSqlSegment().contains("1 = 0"), wrapper.getCustomSqlSegment());
    }

    @Test
    void STRM记录不传文件类型_不加扩展名条件() {
        OpenlistStrmRestController controller = strmControllerWith(Set.of("mkv"), Set.of("srt"));
        QueryWrapper<OpenlistStrmPlus> wrapper = new QueryWrapper<>();

        controller.applyConditions(wrapper, new OpenlistStrmPlus(), null);

        assertFalse(wrapper.getCustomSqlSegment().contains("LIKE"), wrapper.getCustomSqlSegment());
    }

    private static OpenlistStrmRestController strmControllerWith(Set<String> video, Set<String> subtitle) {
        MediaExtensionProvider extensions = mock(MediaExtensionProvider.class);
        when(extensions.videoExtensions()).thenReturn(video);
        when(extensions.subtitleExtensions()).thenReturn(subtitle);
        OpenlistStrmRestController controller = new OpenlistStrmRestController();
        ReflectionTestUtils.setField(controller, "mediaExtensions", extensions);
        return controller;
    }
}
