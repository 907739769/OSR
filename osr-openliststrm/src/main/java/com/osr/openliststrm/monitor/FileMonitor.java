package com.osr.openliststrm.monitor;

import java.util.function.Consumer;

/**
 * 文件监控接口
 *
 * @author: Jack
 * @creat: 2026/1/12 14:40
 */
public interface FileMonitor {

    void start();

    void stop();

    void setListener(Consumer<FileEvent> listener);
}
