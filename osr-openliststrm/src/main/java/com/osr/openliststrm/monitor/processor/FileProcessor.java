package com.osr.openliststrm.monitor.processor;

import java.nio.file.Path;

/**
 * 文件处理器接口
 *
 * @author: Jack
 * @creat: 2026/1/12 14:45
 */
public interface FileProcessor {

    void process(Path file);
}
