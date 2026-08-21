package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.pt.filter.RejectCode;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchLogServiceTest {

    @Mock private IPtSearchLogPlusService logService;

    private SearchLogService service() {
        return new SearchLogService(logService);
    }

    private TorrentInfo torrent(String title, Integer indexerId) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setIndexerId(indexerId);
        return t;
    }

    @Test
    void recordVerdicts_按裁决落库通过与淘汰的原因() {
        when(logService.count(any())).thenReturn(2L);
        List<TorrentFilterEngine.Verdict> verdicts = List.of(
                TorrentFilterEngine.Verdict.accept(torrent("good", 1)),
                TorrentFilterEngine.Verdict.reject(torrent("bad", 1), RejectCode.LOW_SEEDERS, "做种数 1 低于下限 3"));

        service().recordVerdicts(10, 2, SearchLogService.SOURCE_RSS, verdicts);

        ArgumentCaptor<List<PtSearchLogPlus>> captor = ArgumentCaptor.forClass(List.class);
        verify(logService).saveBatch(captor.capture());
        List<PtSearchLogPlus> rows = captor.getValue();
        assertEquals(2, rows.size());
        assertEquals("1", rows.get(0).getAccepted());
        assertEquals("0", rows.get(1).getAccepted());
        // 文案带实际值供逐条排查，码是稳定分类供聚合与统计——两者都要落库
        assertEquals("做种数 1 低于下限 3", rows.get(1).getReason());
        assertEquals(RejectCode.LOW_SEEDERS.value(), rows.get(1).getReasonCode());
        // 通过的行不写码，否则统计会把"通过"也算进淘汰分布
        assertNull(rows.get(0).getReasonCode());
        assertEquals(10, rows.get(0).getSubId());
        assertEquals(2, rows.get(0).getEpisode());
        assertEquals(SearchLogService.SOURCE_RSS, rows.get(0).getSource());
    }

    @Test
    void recordVerdicts_subId为空_不落库() {
        service().recordVerdicts(null, 1, SearchLogService.SOURCE_RSS,
                List.of(TorrentFilterEngine.Verdict.accept(torrent("t", 1))));

        verify(logService, never()).saveBatch(any());
    }

    @Test
    void recordVerdicts_空列表_不落库() {
        service().recordVerdicts(10, 1, SearchLogService.SOURCE_RSS, List.of());

        verify(logService, never()).saveBatch(any());
    }

    @Test
    void recordSummary_写入一条无候选明细的日志() {
        when(logService.count(any())).thenReturn(1L);

        service().recordSummary(10, -1, SearchLogService.SOURCE_SUPPLEMENT, "没有可用的下载器");

        ArgumentCaptor<PtSearchLogPlus> captor = ArgumentCaptor.forClass(PtSearchLogPlus.class);
        verify(logService).save(captor.capture());
        assertEquals("0", captor.getValue().getAccepted());
        assertEquals("没有可用的下载器", captor.getValue().getReason());
        assertEquals(-1, captor.getValue().getEpisode());
    }

    @Test
    void 写库异常_吞掉不向上抛() {
        when(logService.count(any())).thenThrow(new RuntimeException("db down"));

        // 不应抛出异常
        service().recordSummary(10, 1, SearchLogService.SOURCE_RSS, "test");
    }

    @Test
    void 超出触发水位_清理最旧的记录() {
        when(logService.count(any())).thenReturn(305L);
        PtSearchLogPlus stale1 = new PtSearchLogPlus();
        stale1.setId(1);
        PtSearchLogPlus stale2 = new PtSearchLogPlus();
        stale2.setId(2);
        when(logService.list(any(Wrapper.class))).thenReturn(List.of(stale1, stale2));

        service().recordSummary(10, 1, SearchLogService.SOURCE_RSS, "test");

        verify(logService).removeByIds(List.of(1, 2));
    }

    /**
     * 清理是<b>攒批</b>触发的：超过保留条数(200)但没到触发水位(300)时一次库都不该查。
     * <p>
     * 这条钉的是本次改动的全部意义。prune 挂在每一次日志写入末尾，而补搜是逐集调
     * recordSummary 的——退回"每超一条就删一条"的写法后，稳态下一季几十集就会打出几十次
     * SELECT+DELETE，而<b>功能完全正常</b>，只有数据库请求数多了一个量级，
     * 从任何现象上都看不出来。
     * </p>
     */
    @Test
    void 超出保留条数但未到触发水位_不清理也不查询() {
        when(logService.count(any())).thenReturn(250L);

        service().recordSummary(10, 1, SearchLogService.SOURCE_RSS, "test");

        verify(logService, never()).list(any(Wrapper.class));
        verify(logService, never()).removeByIds(any());
    }

    /** 清理时的投影只取 id，不把 torrent_title / reason 两个长文本列白读回来 */
    @Test
    void 清理时只查id列() {
        when(logService.count(any())).thenReturn(305L);
        PtSearchLogPlus stale = new PtSearchLogPlus();
        stale.setId(1);
        when(logService.list(any(Wrapper.class))).thenReturn(List.of(stale));

        service().recordSummary(10, 1, SearchLogService.SOURCE_RSS, "test");

        ArgumentCaptor<Wrapper<PtSearchLogPlus>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(logService).list(captor.capture());
        assertEquals("id", captor.getValue().getSqlSelect());
    }

    @Test
    void 未超出保留条数_不清理() {
        when(logService.count(any())).thenReturn(50L);

        service().recordSummary(10, 1, SearchLogService.SOURCE_RSS, "test");

        verify(logService, never()).removeByIds(any());
    }
}
