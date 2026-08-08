package com.osr.openliststrm.orphan;

/**
 * 孤儿原因常量。前两个是「正向」判定（有记录、查文件），后三个是「反向」判定（有文件、查记录）。
 * <p>
 * 正向扫描永远发现不了反向问题：它的遍历起点是 rename_detail，而反向残骸的特征恰恰是
 * 没有任何记录指向它们——「只删记录不删文件」和「重命名换了一部剧」留下的东西都属于这一类。
 */
public final class OrphanReason {

    private OrphanReason() {
    }

    /** 有记录，但本地重命名产物已不在 */
    public static final String LOCAL_MISSING = "local_missing";

    /** 有记录、本地产物也在，但 STRM 指向的网盘源已删除 */
    public static final String SOURCE_MISSING = "source_missing";

    /** 目标库里的媒体文件（STRM / 视频）在 rename_detail 里查不到对应记录 */
    public static final String LOCAL_EXTRA = "local_extra";

    /** 目录里只剩 NFO / 图片 / 字幕，没有任何媒体文件——重命名换剧后的典型残骸 */
    public static final String METADATA_ONLY = "metadata_only";

    /** 完全空目录 */
    public static final String EMPTY_DIR = "empty_dir";
}
