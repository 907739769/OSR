package com.osr.openliststrm.monitor;

import java.nio.file.Path;

/**
 * 文件事件
 *
 * @author: Jack
 * @creat: 2026/1/12 14:40
 */
public class FileEvent {

    public enum Type {
        CREATE,
        MODIFY
    }

    private final Path path;
    private final Type type;

    public FileEvent(Path path, Type type) {
        this.path = path;
        this.type = type;
    }

    public Path getPath() {
        return path;
    }

    public Type getType() {
        return type;
    }
}
