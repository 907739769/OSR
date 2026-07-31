package com.osr.openliststrm.mybatisplus.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * baseUrl() 需要对用户误填的 host（带协议前缀、带末尾斜杠、带首尾空白）做防御性清洗，
 * 避免拼出 http://http://host:port 这种畸形地址。
 *
 * @author Jack
 */
class PtDownloaderPlusTest {

    private PtDownloaderPlus downloader(String host, Integer port, String useHttps) {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setHost(host);
        d.setPort(port);
        d.setUseHttps(useHttps);
        return d;
    }

    @Test
    void baseUrl_正常host不使用https_返回http地址() {
        PtDownloaderPlus d = downloader("192.168.1.10", 8080, "0");

        assertEquals("http://192.168.1.10:8080", d.baseUrl());
    }

    @Test
    void baseUrl_正常host使用https_返回https地址() {
        PtDownloaderPlus d = downloader("192.168.1.10", 8080, "1");

        assertEquals("https://192.168.1.10:8080", d.baseUrl());
    }

    @Test
    void baseUrl_host误填http协议前缀_仍拼出正确地址() {
        PtDownloaderPlus d = downloader("http://192.168.1.10", 8080, "0");

        assertEquals("http://192.168.1.10:8080", d.baseUrl());
    }

    @Test
    void baseUrl_host误填https协议前缀且使用https_拼出正确https地址() {
        PtDownloaderPlus d = downloader("https://192.168.1.10", 8080, "1");

        assertEquals("https://192.168.1.10:8080", d.baseUrl());
    }

    @Test
    void baseUrl_host末尾带斜杠_斜杠被去掉() {
        PtDownloaderPlus d = downloader("192.168.1.10/", 8080, "0");

        assertEquals("http://192.168.1.10:8080", d.baseUrl());
    }

    @Test
    void baseUrl_host首尾有空格_空格被去掉() {
        PtDownloaderPlus d = downloader("  192.168.1.10  ", 8080, "0");

        assertEquals("http://192.168.1.10:8080", d.baseUrl());
    }

    @Test
    void baseUrl_不改写字段本身的host值() {
        PtDownloaderPlus d = downloader("http://192.168.1.10/", 8080, "0");

        d.baseUrl();

        assertEquals("http://192.168.1.10/", d.getHost());
    }

    @Test
    void maxConcurrent_未设置时为null_设置后能读回() {
        PtDownloaderPlus d = new PtDownloaderPlus();

        assertNull(d.getMaxConcurrent());

        d.setMaxConcurrent(5);

        assertEquals(5, d.getMaxConcurrent());
    }
}
