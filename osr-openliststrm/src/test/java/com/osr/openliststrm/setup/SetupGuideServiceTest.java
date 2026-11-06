package com.osr.openliststrm.setup;
import com.osr.openliststrm.mybatisplus.service.*;
import com.osr.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SetupGuideServiceTest {
    @Test void placeholdersAreNotConfiguredAndCredentialsNeverReturned() {
        var config=mock(ISysConfigService.class);
        when(config.selectConfigByKey("openlist.server.url")).thenReturn("http://xxx:5244");
        when(config.selectConfigByKey("openlist.server.token")).thenReturn("never-return-this-secret");
        var service=new SetupGuideService(config,mock(IOpenlistStrmTaskPlusService.class),mock(IOpenlistCopyTaskPlusService.class),
                mock(IRenameTaskPlusService.class),mock(IPtIndexerPlusService.class),mock(IPtDownloaderPlusService.class),
                mock(IPtMediaServerPlusService.class),mock(IOpenlistCopyPlusService.class),mock(IOpenlistStrmPlusService.class),mock(IRenameDetailPlusService.class));
        var status=service.status("PT");
        assertFalse(status.stream().filter(s->s.key().equals("openlist")).findFirst().orElseThrow().configured());
        assertFalse(status.toString().contains("never-return-this-secret"));
        assertThrows(IllegalArgumentException.class,()->service.verify("/private/file.mkv"));
        assertThrows(IllegalArgumentException.class,()->service.status("invalid"));
        assertTrue(service.verify("sample.mkv").stream().allMatch(e->e.state().equals("UNKNOWN")));
    }
}
