package com.osr.openliststrm.pt.media;

/**
 * 媒体服务器上一个媒体库的一个目录（一个库可以挂多个目录，每个目录一条）。
 *
 * @param name 媒体库名，只用于日志与页面展示
 * @param path 媒体服务器视角的目录路径
 * @param key  局部刷新时定位这个库要用的标识：Plex 是 section key，Emby/Jellyfin 用不到（按路径通知）
 */
public record LibraryRoot(String name, String path, String key) {
}
