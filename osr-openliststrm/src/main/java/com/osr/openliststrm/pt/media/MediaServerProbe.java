package com.osr.openliststrm.pt.media;

/**
 * 连通性测试的结果。
 *
 * <p><b>为什么不是一个 boolean。</b>「连接失败」这三个字回答不了用户接下来该做什么：
 * API Key 填错、地址/端口不通、反向代理把请求转到了别的服务——三者的处置方向完全不同，
 * 而它们在旧实现里被压成同一句「连接失败，请检查地址、API Key 与网络」，真实原因只留在
 * 后端日志里，用户手上就是那个弹窗。
 *
 * <p>成功那侧同理：{@code /System/Info} 本来就返回了版本与服务器名，旧实现取出来只
 * {@code log.info} 了一下。把它回显出来，用户才能确认自己连上的是不是心里想的那一台——
 * 配了反代、做了端口映射、或者局域网里跑着两个 Emby 时，这一句省掉的排查不少。
 *
 * @param ok     是否连通
 * @param detail 连通时是「Emby 4.8.0.80 · 客厅」这类描述，不通时是能指导处置的失败原因
 * @author Jack
 */
public record MediaServerProbe(boolean ok, String detail) {

    public static MediaServerProbe success(String detail) {
        return new MediaServerProbe(true, detail);
    }

    public static MediaServerProbe failure(String reason) {
        return new MediaServerProbe(false, reason);
    }
}
