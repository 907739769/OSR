package com.osr.openliststrm.monitor.processor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 文件稳定性检测：通过间隔采样文件大小/修改时间判断文件是否仍在写入中。
 * 从 MediaRenameProcessor / MediaUploadProcessor 中提取的公共逻辑。
 */
final class FileStabilityUtils {

    private FileStabilityUtils() {
    }

    static boolean isFileStable(Path p) {
        try {
            long s1 = Files.size(p);
            long t1 = Files.getLastModifiedTime(p).toMillis();
            TimeUnit.SECONDS.sleep(2);
            long s2 = Files.size(p);
            long t2 = Files.getLastModifiedTime(p).toMillis();
            return s1 == s2 && t1 == t2;
        } catch (Exception ignored) {
        }
        return false;
    }
}
