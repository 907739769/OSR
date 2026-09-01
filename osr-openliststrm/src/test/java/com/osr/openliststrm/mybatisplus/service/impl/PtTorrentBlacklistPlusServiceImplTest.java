package com.osr.openliststrm.mybatisplus.service.impl;

import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtTorrentBlacklistPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.pt.task.dto.BatchBlacklistResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * blockRecordGuid/blockRecordReleaseGroup 是本类唯一有真实逻辑的方法，
 * save()/updateById() 重载负责管理页新增/编辑的类型限制与归一化。
 *
 * @author Jack
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtTorrentBlacklistPlusServiceImplTest {

    @Mock
    private PtTorrentBlacklistPlusMapper baseMapper;
    @Mock
    private IPtDownloadRecordPlusService recordService;

    private PtTorrentBlacklistPlusServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PtTorrentBlacklistPlusServiceImpl(recordService);
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);
    }

    private PtDownloadRecordPlus record(Integer id, String title, String guidHash) {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(id);
        r.setTitle(title);
        r.setGuidHash(guidHash);
        return r;
    }

    // ---------- blockRecordGuid ----------

    @Test
    void blockRecordGuid_记录存在且未拉黑过_新增一行() {
        when(recordService.getById(1)).thenReturn(record(1, "Some Title", "hash1"));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(PtTorrentBlacklistPlus.class))).thenReturn(1);

        boolean result = service.blockRecordGuid(1, null);

        assertTrue(result);
        ArgumentCaptor<PtTorrentBlacklistPlus> captor = ArgumentCaptor.forClass(PtTorrentBlacklistPlus.class);
        verify(baseMapper).insert(captor.capture());
        PtTorrentBlacklistPlus saved = captor.getValue();
        assertEquals("GUID", saved.getType());
        assertEquals("hash1", saved.getValue());
        assertEquals("Some Title", saved.getDisplayValue());
        assertTrue(saved.getReason() != null && !saved.getReason().isBlank());
    }

    @Test
    void blockRecordGuid_重复调用同一记录_不重复插入返回false() {
        when(recordService.getById(1)).thenReturn(record(1, "Some Title", "hash1"));
        when(baseMapper.selectCount(any())).thenReturn(1L);

        boolean result = service.blockRecordGuid(1, null);

        assertFalse(result);
        verify(baseMapper, never()).insert(any(PtTorrentBlacklistPlus.class));
    }

    @Test
    void blockRecordGuid_记录不存在_抛IllegalArgumentException() {
        when(recordService.getById(999)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.blockRecordGuid(999, null));
    }

    // ---------- blockRecordReleaseGroup ----------

    @Test
    void blockRecordReleaseGroup_标题能解析出发布组_新增一行value为大写发布组() {
        // 注：标题末尾补了 ".mkv"，区别于简报原文里的 "...H264-chdweb"（无扩展名）。
        // 种子标题不带扩展名，就是 PT 站上的原样。此前这里补过一个占位的 ".mkv"，理由写的是
        // "MediaParser.extractBase 会把最后一段当扩展名剥离"——那是 parse() 的行为，
        // blockRecordReleaseGroup 走的 parseLocal() 传的是 stripExtension=false，本来就不剥扩展名。
        // 补上 ".mkv" 反而让标题以 " mkv" 结尾，SourceAndGroupExtractor 的结尾段判定
        // （要求那一段由 - / @ 引导或自带连字符）匹配不到发布组，服务方法直接抛"无法从标题解析出发布组"，
        // 本用例因此长期报错。SubscriptionEngineTest 里有同源的一处，已一并修正。
        when(recordService.getById(1)).thenReturn(
                record(1, "Show.Name.S01E01.1080p.WEB-DL.H264-chdweb", "hash1"));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(PtTorrentBlacklistPlus.class))).thenReturn(1);

        boolean result = service.blockRecordReleaseGroup(1, "画质差");

        assertTrue(result);
        ArgumentCaptor<PtTorrentBlacklistPlus> captor = ArgumentCaptor.forClass(PtTorrentBlacklistPlus.class);
        verify(baseMapper).insert(captor.capture());
        PtTorrentBlacklistPlus saved = captor.getValue();
        assertEquals("RELEASE_GROUP", saved.getType());
        assertEquals("CHDWEB", saved.getValue());
        assertEquals("chdweb", saved.getDisplayValue());
        assertEquals("画质差", saved.getReason());
    }

    @Test
    void blockRecordReleaseGroup_标题解析不出发布组_抛IllegalArgumentException() {
        when(recordService.getById(1)).thenReturn(record(1, "纯中文电影标题", "hash1"));

        assertThrows(IllegalArgumentException.class, () -> service.blockRecordReleaseGroup(1, null));
    }

    @Test
    void blockRecordReleaseGroup_记录不存在_抛IllegalArgumentException() {
        when(recordService.getById(999)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.blockRecordReleaseGroup(999, null));
    }

    // ---------- save()/updateById() 类型限制与归一化 ----------

    @Test
    void save_type为GUID_一律拒绝() {
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType("GUID");
        entity.setValue("somehash");

        assertThrows(IllegalArgumentException.class, () -> service.save(entity));
        verify(baseMapper, never()).insert(any(PtTorrentBlacklistPlus.class));
    }

    @Test
    void save_type为RELEASE_GROUP_value落库前归一化为去空白加大写() {
        when(baseMapper.insert(any(PtTorrentBlacklistPlus.class))).thenReturn(1);
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType("RELEASE_GROUP");
        entity.setValue("  chdweb  ");

        boolean result = service.save(entity);

        assertTrue(result);
        assertEquals("CHDWEB", entity.getValue());
    }

    @Test
    void save_type为RELEASE_GROUP_未填displayValue时回填为原始value() {
        when(baseMapper.insert(any(PtTorrentBlacklistPlus.class))).thenReturn(1);
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType("RELEASE_GROUP");
        entity.setValue("  chdweb  ");

        service.save(entity);

        assertEquals("chdweb", entity.getDisplayValue());
    }

    @Test
    void updateById_type为GUID_一律拒绝() {
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setId(5);
        entity.setType("GUID");

        assertThrows(IllegalArgumentException.class, () -> service.updateById(entity));
        verify(baseMapper, never()).updateById(any(PtTorrentBlacklistPlus.class));
    }

    @Test
    void updateById_type为RELEASE_GROUP_value落库前归一化() {
        when(baseMapper.updateById(any(PtTorrentBlacklistPlus.class))).thenReturn(1);
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setId(5);
        entity.setType("RELEASE_GROUP");
        entity.setValue(" mteam ");

        boolean result = service.updateById(entity);

        assertTrue(result);
        assertEquals("MTEAM", entity.getValue());
    }

    // ---------- 批量拉黑 ----------

    @Test
    void blockRecordGuidBatch_逐条统计新增与幂等_单条不存在不影响其余() {
        when(recordService.getById(1)).thenReturn(record(1, "T1", "hash1"));
        when(recordService.getById(2)).thenReturn(record(2, "T2", "hash2"));
        when(recordService.getById(3)).thenReturn(null);
        // 第一条未拉黑过、第二条已在黑名单中
        when(baseMapper.selectCount(any())).thenReturn(0L, 1L);
        when(baseMapper.insert(any(PtTorrentBlacklistPlus.class))).thenReturn(1);

        BatchBlacklistResult result = service.blockRecordGuidBatch(List.of(1, 2, 3), null);

        assertEquals(3, result.getTotal());
        assertEquals(1, result.getAddedCount());
        assertEquals(1, result.getDuplicateCount());
        assertEquals(1, result.getFailedCount());
        verify(baseMapper, times(1)).insert(any(PtTorrentBlacklistPlus.class));
    }

    @Test
    void blockRecordReleaseGroupBatch_解析不出发布组的记录计入失败_不中断整批() {
        when(recordService.getById(1)).thenReturn(record(1, "Some.Show.S01E01.1080p.WEB-DL-CHDWEB", "hash1"));
        when(recordService.getById(2)).thenReturn(record(2, "", "hash2"));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(PtTorrentBlacklistPlus.class))).thenReturn(1);

        BatchBlacklistResult result = service.blockRecordReleaseGroupBatch(List.of(1, 2), null);

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getAddedCount());
        assertEquals(0, result.getDuplicateCount());
        assertEquals(1, result.getFailedCount());
    }

    @Test
    void 批量拉黑空列表_不碰数据库() {
        BatchBlacklistResult result = service.blockRecordGuidBatch(List.of(), null);

        assertEquals(0, result.getTotal());
        verify(baseMapper, never()).insert(any(PtTorrentBlacklistPlus.class));
    }
}
