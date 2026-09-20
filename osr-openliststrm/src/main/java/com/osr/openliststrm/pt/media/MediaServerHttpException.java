package com.osr.openliststrm.pt.media;

import java.io.IOException;

/**
 * 媒体服务器返回了非 2xx。
 *
 * <p>单独立一个类型只为一件事：让调用方拿得到<b>状态码</b>。401 与 404 与 502 对用户的意义
 * 完全不同（Key 错 / 地址错 / 反代或对端故障），而把它们压回字符串再让上层去解析消息文本，
 * 是那种改一次文案就静默失效的写法。
 *
 * <p>它仍是 {@link IOException} 的子类，因此「网络异常 → IOException → 调用方本轮跳过、
 * 下轮重来」这条既有契约一字不变，既有的 catch 一处都不用改。
 *
 * @author Jack
 */
public class MediaServerHttpException extends IOException {

    private final int statusCode;

    public MediaServerHttpException(int statusCode) {
        super("媒体服务器返回 HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
