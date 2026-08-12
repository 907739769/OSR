package com.osr.openliststrm.service;

/**
 * 一次 STRM 生成实际生效的可覆盖设置，由 {@link StrmSettingsFactory} 把全局配置与任务级
 * 覆盖合并得到。
 *
 * @param outputDir   STRM 输出根目录
 * @param downloadSub 是否顺带下载字幕文件
 * @param minSize     视频文件最小体积（字节），小于此值不生成 STRM
 *
 * @author Jack
 */
public record StrmSettings(String outputDir, boolean downloadSub, long minSize) {
}
