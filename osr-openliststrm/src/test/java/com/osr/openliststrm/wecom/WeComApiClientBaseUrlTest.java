package com.osr.openliststrm.wecom;

import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 企微 API 代理地址的规范化。
 * <p>
 * 这个配置由用户手填，形态五花八门；而它一旦产出非法串，{@code HttpUrl.parse} 会返回 null，
 * 一路带到调用处就是 NPE——偏偏整条通知/指令链路是异步跑的，异常只会安静地写进日志。
 * 所以这里把每种输入都钉一条，并统一断言结果能被 OkHttp 解析。
 */
class WeComApiClientBaseUrlTest {

    private static final String DEFAULT = "https://qyapi.weixin.qq.com/cgi-bin/";

    @Test
    void 未配置_回退官方地址() {
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase(null));
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase(""));
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase("   "));
    }

    @Test
    void 填官方地址_与默认值等价() {
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase("https://qyapi.weixin.qq.com"));
    }

    @Test
    void 代理地址_自动补cgi_bin() {
        assertEquals("https://wx.example.workers.dev/cgi-bin/",
                WeComApiClient.resolveApiBase("https://wx.example.workers.dev"));
    }

    @Test
    void 代理地址带尾斜杠_不产生双斜杠() {
        assertEquals("https://wx.example.workers.dev/cgi-bin/",
                WeComApiClient.resolveApiBase("https://wx.example.workers.dev/"));
    }

    /** 反代配置里把 /cgi-bin 也写上很常见，不能拼成 /cgi-bin/cgi-bin/ */
    @Test
    void 代理地址已含cgi_bin_不重复拼接() {
        assertEquals("https://wx.example.workers.dev/cgi-bin/",
                WeComApiClient.resolveApiBase("https://wx.example.workers.dev/cgi-bin"));
        assertEquals("https://wx.example.workers.dev/cgi-bin/",
                WeComApiClient.resolveApiBase("https://wx.example.workers.dev/cgi-bin/"));
    }

    @Test
    void 代理地址带子路径_保留子路径() {
        assertEquals("https://proxy.example.com/wecom/cgi-bin/",
                WeComApiClient.resolveApiBase("https://proxy.example.com/wecom"));
    }

    @Test
    void 代理地址带端口_保留端口() {
        assertEquals("http://192.168.1.10:8080/cgi-bin/",
                WeComApiClient.resolveApiBase("http://192.168.1.10:8080"));
    }

    @Test
    void 缺少scheme_回退官方地址() {
        // HttpUrl.parse("qyapi.weixin.qq.com/cgi-bin/gettoken") 会返回 null
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase("qyapi.weixin.qq.com"));
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase("wx.example.workers.dev"));
    }

    @Test
    void 非http协议_回退官方地址() {
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase("ftp://wx.example.com"));
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase("ws://wx.example.com"));
    }

    @Test
    void 只有斜杠_回退官方地址() {
        assertEquals(DEFAULT, WeComApiClient.resolveApiBase("///"));
    }

    /** scheme 大小写不该影响判定 */
    @Test
    void scheme大小写不敏感() {
        assertEquals("HTTPS://wx.example.com/cgi-bin/",
                WeComApiClient.resolveApiBase("HTTPS://wx.example.com"));
    }

    /**
     * 最终防线：无论输入什么，产出都必须能被 OkHttp 解析成 URL，
     * 否则调用处的 HttpUrl.parse 会返回 null。
     */
    @Test
    void 任意输入_产出的地址都能被OkHttp解析() {
        String[] inputs = {
                null, "", "  ", "https://qyapi.weixin.qq.com", "https://wx.example.workers.dev/",
                "https://wx.example.workers.dev/cgi-bin", "http://192.168.1.10:8080",
                "qyapi.weixin.qq.com", "ftp://x.com", "///", "https://proxy.example.com/wecom"
        };
        for (String input : inputs) {
            String base = WeComApiClient.resolveApiBase(input);
            assertNotNull(HttpUrl.parse(base + "gettoken"),
                    "输入 [" + input + "] 产出的地址无法解析：" + base);
            assertNotNull(HttpUrl.parse(base + "message/send"),
                    "输入 [" + input + "] 产出的地址无法解析：" + base);
        }
    }
}
