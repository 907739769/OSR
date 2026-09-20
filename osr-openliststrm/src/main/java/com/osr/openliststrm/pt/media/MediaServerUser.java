package com.osr.openliststrm.pt.media;

/**
 * 媒体服务器上的一个用户，供配置页填「用户ID」时选取。
 *
 * <p>这个 ID 在 Emby/Jellyfin 上是一串 32 位十六进制，用户得去 Web 控制台的 URL 里抠出来——
 * 而 {@code /Users} 接口本来就能列出来，没有理由让人手抄。
 *
 * @param id   用户 ID，即最终写进 {@code pt_media_server.user_id} 的值
 * @param name 显示名
 * @author Jack
 */
public record MediaServerUser(String id, String name) {
}
