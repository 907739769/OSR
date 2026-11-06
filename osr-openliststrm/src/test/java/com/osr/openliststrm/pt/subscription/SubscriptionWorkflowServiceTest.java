package com.osr.openliststrm.pt.subscription;
import com.osr.openliststrm.mybatisplus.domain.*;
import com.osr.openliststrm.mybatisplus.service.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class SubscriptionWorkflowServiceTest {
    @Test void inaccessibleSharedStagesAreNotQueriedAndExactDownloadIsLinked() {
        var episodes=mock(IPtSubscriptionEpisodePlusService.class);var downloads=mock(IPtDownloadRecordPlusService.class);
        var renames=mock(IRenameDetailPlusService.class);var copies=mock(IOpenlistCopyPlusService.class);var strms=mock(IOpenlistStrmPlusService.class);
        var service=new SubscriptionWorkflowService(episodes,downloads,renames,copies,strms);
        var sub=new PtSubscriptionPlus();sub.setId(1);
        var ep=new PtSubscriptionEpisodePlus();ep.setEpisode(2);ep.setDownloadId(8);
        when(episodes.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(ep));
        var download=new PtDownloadRecordPlus();download.setId(8);download.setSubId(1);download.setState("COMPLETED");
        when(downloads.getById(8)).thenReturn(download);
        var result=service.inspect(sub,false);
        assertEquals(8,result.getFirst().steps().getFirst().recordId());
        assertEquals("recordId",result.getFirst().steps().getFirst().recordKey());
        verifyNoInteractions(renames,copies,strms);
    }
    @Test void missingAssociationsAreUnknownInsteadOfFailure() {
        var episodes=mock(IPtSubscriptionEpisodePlusService.class);var downloads=mock(IPtDownloadRecordPlusService.class);
        var renames=mock(IRenameDetailPlusService.class);var copies=mock(IOpenlistCopyPlusService.class);var strms=mock(IOpenlistStrmPlusService.class);
        var service=new SubscriptionWorkflowService(episodes,downloads,renames,copies,strms);
        var sub=new PtSubscriptionPlus();sub.setId(1);sub.setMediaType("TV");sub.setTmdbId("100");sub.setSeason(1);
        var ep=new PtSubscriptionEpisodePlus();ep.setEpisode(2);
        when(episodes.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(ep));
        var result=service.inspect(sub,true);
        assertTrue(result.getFirst().steps().stream().allMatch(s->"UNKNOWN".equals(s.state())));
    }
}
