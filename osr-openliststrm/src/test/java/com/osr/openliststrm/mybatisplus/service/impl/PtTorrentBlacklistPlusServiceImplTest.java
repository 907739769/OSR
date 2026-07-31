package com.osr.openliststrm.mybatisplus.service.impl;

import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtTorrentBlacklistPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
        // MediaParser.extractBase() 对入参统一按"取最后一个'.'之后的部分当扩展名"处理，
        // parseLocal 的 Javadoc 虽然声明"允许没有扩展名"，但一旦标题里还有其它的点号
        // （PT 场景幾乎总是如此，如 WEB-DL.H264-GROUP 这种惯用连字），最后一段就会被当成
        // 伪扩展名整段丢弃，连带把发布组吃掉——MediaParserLocalTest.java 里能通过的
        // "H264-GROUP" 用例同样是靠末尾真实扩展名 ".mkv" 才躲开这个坑。
        // 这是 MediaParser 既有行为，不属于本任务(黑名单三件套)能改动的范围，故照抄该写法，
        // 而不放宽/更改断言。这个坑已经在报告里作为独立疑虑记录。
        when(recordService.getById(1)).thenReturn(
                record(1, "Show.Name.S01E01.1080p.WEB-DL.H264-chdweb.mkv", "hash1"));
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
}
