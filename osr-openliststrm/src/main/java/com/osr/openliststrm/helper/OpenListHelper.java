package com.osr.openliststrm.helper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @Author Jack
 * @Date 2025/7/16 20:51
 * @Version 1.0.0
 */
@Component
public class OpenListHelper {

    @Autowired
    private SysDictDataHelper sysDictDataHelper;

    /**
     * 判断文件是视频文件
     *
     * @param name
     * @return
     */
    public boolean isVideo(String name) {
        return matchesExtension(name, "openlist_video_type");
    }

    /**
     * 判断文件是字幕文件
     *
     * @param name
     * @return
     */
    public boolean isSrt(String name) {
        return matchesExtension(name, "openlist_srt_type");
    }

    /**
     * 按扩展名匹配字典类型。提取一次扩展名并查缓存的小写集合，
     * 避免在热循环里对每个字典值重复 toLowerCase。
     */
    private boolean matchesExtension(String name, String dictType) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase();
        return sysDictDataHelper.getAllValueLowerSet(dictType).contains(ext);
    }

    /**
     * 判断文件是 .strm 文件（不区分大小写）
     * 如果是 .strm 文件，应直接处理，不受最小文件大小限制
     *
     * @param name 文件名或路径
     * @return true 如果以 .strm 结尾
     */
    public boolean isStrm(String name) {
        if (name == null) {
            return false;
        }
        return name.toLowerCase().endsWith(".strm");
    }

}
