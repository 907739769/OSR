package com.osr.openliststrm.rename.extractor;

import com.osr.openliststrm.rename.model.MediaInfo;

/**
 * @Author Jack
 * @Date 2025/8/12 16:38
 * @Version 1.0.0
 */
public interface Extractor {
    /**
     * 从 name 中抽取并设置 MediaInfo 字段。返回剩余未处理的字符串（用于下一步 extractor）。
     */
    String extract(String name, MediaInfo info);
}