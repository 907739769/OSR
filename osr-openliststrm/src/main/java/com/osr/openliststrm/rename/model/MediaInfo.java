package com.osr.openliststrm.rename.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author Jack
 * @Date 2025/8/12 16:37
 * @Version 1.0.0
 */
@Data
public class MediaInfo {
    private String originalName;
    private String title; // 最终确定的标题（优先中文TMDb，否则原始处理结果）
    private String originalTitle; // 从文件名抽取的原始标题，优先中文
    private String englishTitle; // 从文件名抽取的英文标题
    private String year;
    private String season; // S
    private String episode; // E or episode number
    private String episodeEnd; // 区间结尾集数，如 S01E01-03 中的 "03"；非区间时为空
    private String tmdbId;

    // 集级元数据（从 TMDB /tv/{id}/season/{number} 获取）
    private String episodeName; // 集标题（中文）
    private String episodeTmdbId; // 单集 TMDB ID (tv episode id)
    private String episodePlot; // 单集剧情简介
    private String episodeAiredDate; // 单集播出日期 YYYY-MM-DD
    private String episodeRating; // 单集评分
    private String episodeDirector; // 单集导演
    private String episodeWriter; // 单集编剧
    private String episodeGuestStars; // 单集客串演员（逗号分隔）
    private String episodeStillPath; // 单集剧照路径（TMDb file_path）

    private String resolution; // e.g., 2160p, 1080p, 1080i
    private String videoCodec; // H264, H265, x264, HEVC
    private String audioCodec; // AAC, DDP5.1, DTS
    private String source; // WEB-DL, BluRay, HDTV, NF, AMZN, DSNP, etc
    private List<String> tags = new ArrayList<>(); // 特效、标签、HDR,10bit,60fps etc
    private String releaseGroup; // 发布组 HHWEB, MWeb
    private String extension;//文件后缀

    // TMDb / metadata fields (may be populated by TMDb client)
    private List<String> genreIds = new ArrayList<>();
    private String originalLanguage; // e.g. "zh", "en"
    private List<String> originCountries = new ArrayList<>();
    private Map<String, Object> metadata = new HashMap<>();

    public MediaInfo(String originalName) {
        this.originalName = originalName;
    }

    public String getOriginalTitle() { return originalTitle; }
    public void setOriginalTitle(String originalTitle) { this.originalTitle = originalTitle; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getYear() { return year; }
    public void setYear(String year) { this.year = year; }
    public String getSeason() { return season; }
    public void setSeason(String season) { this.season = season; }
    public String getEpisode() { return episode; }
    public void setEpisode(String episode) { this.episode = episode; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getEnglishTitle() { return englishTitle; }
    public void setEnglishTitle(String englishTitle) { this.englishTitle = englishTitle; }
    public String getTmdbId() { return tmdbId; }
    public void setTmdbId(String tmdbId) { this.tmdbId = tmdbId; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getReleaseGroup() { return releaseGroup; }
    public void setReleaseGroup(String releaseGroup) { this.releaseGroup = releaseGroup; }
    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }
    public List<String> getGenreIds() { return genreIds; }
    public void setGenreIds(List<String> genreIds) { this.genreIds = genreIds; }
    public String getOriginalLanguage() { return originalLanguage; }
    public void setOriginalLanguage(String originalLanguage) { this.originalLanguage = originalLanguage; }
    public List<String> getOriginCountries() { return originCountries; }
    public void setOriginCountries(List<String> originCountries) { this.originCountries = originCountries; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public String getEpisodeRating() { return episodeRating; }
    public void setEpisodeRating(String episodeRating) { this.episodeRating = episodeRating; }
    public String getEpisodeDirector() { return episodeDirector; }
    public void setEpisodeDirector(String episodeDirector) { this.episodeDirector = episodeDirector; }
    public String getEpisodeWriter() { return episodeWriter; }
    public void setEpisodeWriter(String episodeWriter) { this.episodeWriter = episodeWriter; }
    public String getEpisodeGuestStars() { return episodeGuestStars; }
    public void setEpisodeGuestStars(String episodeGuestStars) { this.episodeGuestStars = episodeGuestStars; }
    public String getEpisodeStillPath() { return episodeStillPath; }
    public void setEpisodeStillPath(String episodeStillPath) { this.episodeStillPath = episodeStillPath; }
}
