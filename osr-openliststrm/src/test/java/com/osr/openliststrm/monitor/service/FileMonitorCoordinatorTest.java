package com.osr.openliststrm.monitor.service;

import com.osr.openliststrm.monitor.FileMonitor;
import com.osr.openliststrm.monitor.processor.FileProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class FileMonitorCoordinatorTest {
    @Test
    void 停止目录监控时关闭处理器_监控停止异常也会取消延后任务() {
        FileMonitor monitor = mock(FileMonitor.class);
        FileProcessor processor = mock(FileProcessor.class);
        FileMonitorCoordinator coordinator = new FileMonitorCoordinator(monitor, processor);
        doThrow(new IllegalStateException("stop failed")).when(monitor).stop();
        assertThrows(IllegalStateException.class, coordinator::stop);
        verify(processor).close();
    }
}
