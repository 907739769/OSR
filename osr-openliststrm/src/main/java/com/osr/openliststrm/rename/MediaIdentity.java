package com.osr.openliststrm.rename;

/** 确定的作品身份；类型使用 TMDb 的小写 movie/tv，季集号允许为空。 */
public record MediaIdentity(String tmdbId, String mediaType, String season, String episode) {
    public MediaIdentity {
        if (tmdbId == null || !tmdbId.matches("[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("TMDb ID 必须为正整数");
        }
        if (!"movie".equals(mediaType) && !"tv".equals(mediaType)) {
            throw new IllegalArgumentException("请选择电影或电视剧");
        }
        if ("movie".equals(mediaType)) {
            season = null;
            episode = null;
        } else {
            if (season != null && !season.isBlank() && !season.matches("[0-9]{1,4}")) {
                throw new IllegalArgumentException("季号必须为 1-4 位数字");
            }
            if (episode != null && !episode.isBlank() && !episode.matches("[0-9]{1,4}")) {
                throw new IllegalArgumentException("集号必须为 1-4 位数字");
            }
            // 01 与 1 表示同一季，避免辅种或重复下载产生虚假的身份冲突。
            season = season == null || season.isBlank() ? null : String.valueOf(Integer.parseInt(season));
            episode = episode == null || episode.isBlank() ? null : String.valueOf(Integer.parseInt(episode));
        }
    }
}
