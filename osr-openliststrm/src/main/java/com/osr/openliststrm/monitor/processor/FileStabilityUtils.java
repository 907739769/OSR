package com.osr.openliststrm.monitor.processor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 文件稳定性检测：通过间隔采样文件大小/修改时间判断文件是否仍在写入中。
 * 从 MediaRenameProcessor / MediaUploadProcessor 中提取的公共逻辑。
 */
final class FileStabilityUtils {

    /**
     * 下载器/网盘客户端的在途文件后缀。清单原先散在两个 processor 里各写一份，
     * 只有 {@code .!qB / .part / .tmp} 三个。
     */
    private static final Set<String> TRANSIENT_SUFFIXES = Set.of(
            ".!qB", ".!ut", ".part", ".parts", ".tmp", ".temp",
            ".downloading", ".aria2", ".crdownload", ".rclonelink", ".partial");

    private FileStabilityUtils() {
    }

    /**
     * 是不是下载器/网盘客户端的在途产物，不该进入上传与重命名流程。
     * <p>
     * 两条判据。后缀清单挡已知形态；<b>点开头一律跳过</b>挡未知形态——媒体文件不会以点开头，
     * 而各家客户端的临时文件几乎都是点开头加一串哈希。
     * </p>
     * <p>
     * 实测踩过一次：115 客户端写的是 {@code .7a6b05….parts}（<b>复数 s</b>），
     * 而清单里只有 {@code .part}，{@code endsWith(".part")} 不匹配，于是这个临时文件的
     * <b>每一次写入事件</b>都进了稳定性判定——而 {@link #isFileStable} 要 {@code sleep(2)}。
     * 生产日志里 3.016 秒内出现 569 个事件、各起一个异步任务，也就是同一个临时文件上
     * 同时挂着 569 个各睡两秒的线程，外加 569 行「文件仍在写入」。
     * </p>
     */
    static boolean isTransientArtifact(Path p) {
        Path name = p.getFileName();
        if (name == null) return false;
        String fn = name.toString();
        if (fn.startsWith(".")) return true;
        for (String suffix : TRANSIENT_SUFFIXES) {
            if (fn.regionMatches(true, fn.length() - suffix.length(), suffix, 0, suffix.length())) {
                return true;
            }
        }
        return false;
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
