package com.osr.openliststrm.orphan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StrmSourcePathResolverTest {

    @Test
    void resolve_未编码内容_直接截取baseUrl和d前缀后的路径() {
        String content = "http://192.168.1.10:5244/d/movies/Inception (2010)/Inception.mkv";
        String result = StrmSourcePathResolver.resolve(content, "http://192.168.1.10:5244", false);
        assertEquals("/movies/Inception (2010)/Inception.mkv", result);
    }

    @Test
    void resolve_已编码内容_还原出原始网盘路径() {
        // 对应源路径 "/movies/盗梦空间 (2010)/盗梦空间 1.mkv"，空格编码为%20，中文按UTF-8百分号编码，"/"保持不变
        String content = "http://192.168.1.10:5244/d/movies/%E7%9B%97%E6%A2%A6%E7%A9%BA%E9%97%B4%20(2010)/%E7%9B%97%E6%A2%A6%E7%A9%BA%E9%97%B4%201.mkv";
        String result = StrmSourcePathResolver.resolve(content, "http://192.168.1.10:5244", true);
        assertEquals("/movies/盗梦空间 (2010)/盗梦空间 1.mkv", result);
    }

    @Test
    void resolve_baseUrl前缀不匹配_返回null() {
        // 用户中途换过OpenList域名，历史.strm文件里的baseUrl跟当前配置对不上
        String content = "http://old-domain.example.com:5244/d/movies/Inception.mkv";
        String result = StrmSourcePathResolver.resolve(content, "http://192.168.1.10:5244", false);
        assertNull(result);
    }

    @Test
    void resolve_内容为空_返回null() {
        assertNull(StrmSourcePathResolver.resolve(null, "http://192.168.1.10:5244", false));
        assertNull(StrmSourcePathResolver.resolve("", "http://192.168.1.10:5244", false));
    }

    @Test
    void resolve_baseUrl为空_返回null() {
        assertNull(StrmSourcePathResolver.resolve("http://192.168.1.10:5244/d/movies/a.mkv", null, false));
    }
}
