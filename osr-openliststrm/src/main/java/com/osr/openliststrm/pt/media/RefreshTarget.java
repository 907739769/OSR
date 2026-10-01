package com.osr.openliststrm.pt.media;

/**
 * 一次局部刷新的目标。
 *
 * @param root 目录所在的媒体库
 * @param path 要刷新的目录（媒体服务器视角）；null 表示整个库——一批里同一个库的目录太多时
 *             收缩成整库，比逐个目录发请求更省
 */
public record RefreshTarget(LibraryRoot root, String path) {

    /** 实际要扫描的目录：整库时就是库目录本身 */
    public String effectivePath() {
        return path == null ? root.path() : path;
    }
}
