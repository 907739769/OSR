package com.osr.openliststrm.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.rename.model.MediaInfo;

import static com.osr.openliststrm.scrape.NfoXmlBuilder.*;

/**
 * 单集（Episode）NFO 构建策略。
 * 根节点: &lt;episodedetails&gt;
 */
public class EpisodeNfoBuilder implements NfoTypeStrategy {

    @Override
    public String buildNfo(MediaInfo info) {
        return wrapXml("episodedetails", sb -> buildBody(sb, info));
    }

    private void buildBody(StringBuilder sb, MediaInfo info) {
        JsonNode details = getDetails(info);

        // 剧集标题优先取 TMDb 接口返回值
        String showTitle = details != null && details.hasNonNull("name") ? details.get("name").asText() : info.getTitle();

        // 集标题
        String epTitle;
        if (StringUtils.isNotBlank(info.getEpisodeName())) {
            epTitle = info.getEpisodeName();
        } else {
            int epNum = parseEpisodeNumber(info.getEpisode());
            epTitle = "第 " + epNum + " 集";
        }
        appendTag(sb, "title", epTitle);

        // showtitle
        appendTag(sb, "showtitle", showTitle);

        // 季/集号（数字）
        int seasonNum = parseSeasonNumber(info.getSeason());
        int epNum = parseEpisodeNumber(info.getEpisode());
        appendTag(sb, "season", String.valueOf(seasonNum));
        appendTag(sb, "episode", String.valueOf(epNum));

        // 单集 tmdbId（仅当有单集独立 ID 时才写入，不回退到剧集 ID）
        if (StringUtils.isNotBlank(info.getEpisodeTmdbId())) {
            appendTag(sb, "tmdbid", info.getEpisodeTmdbId());
            appendUniqueid(sb, info.getEpisodeTmdbId(), "tmdb", "true");
        }

        // 播出日期
        if (StringUtils.isNotBlank(info.getEpisodeAiredDate())) {
            appendTag(sb, "aired", info.getEpisodeAiredDate());
        }

        // 单集评分
        if (StringUtils.isNotBlank(info.getEpisodeRating())) {
            appendTag(sb, "rating", info.getEpisodeRating());
        }

        // 导演
        if (StringUtils.isNotBlank(info.getEpisodeDirector())) {
            appendTag(sb, "director", info.getEpisodeDirector());
        }

        // 编剧
        if (StringUtils.isNotBlank(info.getEpisodeWriter())) {
            appendTag(sb, "writer", info.getEpisodeWriter());
        }

        // 客串演员
        if (StringUtils.isNotBlank(info.getEpisodeGuestStars())) {
            appendTag(sb, "credits", info.getEpisodeGuestStars());
        }

        // 剧情
        String plot = StringUtils.isNotBlank(info.getEpisodePlot())
                ? info.getEpisodePlot()
                : (details != null && details.has("overview") ? details.get("overview").asText() : null);
        appendCDataTag(sb, "plot", plot);

        // 单集剧照 (thumb)
        if (StringUtils.isNotBlank(info.getEpisodeStillPath())) {
            sb.append("  <thumb>").append(TMDb_IMG_BASE).append(escapeXml(info.getEpisodeStillPath())).append("</thumb>\n");
        }

        // fileinfo — 视频编码信息
        boolean hasVideo = StringUtils.isNotBlank(info.getVideoCodec());
        boolean hasAudio = StringUtils.isNotBlank(info.getAudioCodec());
        boolean hasResolution = StringUtils.isNotBlank(info.getResolution());
        if (hasVideo || hasAudio || hasResolution) {
            sb.append("  <fileinfo>\n");
            sb.append("    <streamdetails>\n");
            if (hasVideo) {
                sb.append("      <video>\n");
                sb.append("        <codec>").append(escapeXml(info.getVideoCodec())).append("</codec>\n");
                if (hasResolution) {
                    sb.append("        <width>").append(escapeXml(parseResolutionWidth(info.getResolution()))).append("</width>\n");
                    sb.append("        <height>").append(escapeXml(parseResolutionHeight(info.getResolution()))).append("</height>\n");
                }
                sb.append("      </video>\n");
            }
            if (hasAudio) {
                sb.append("      <audio>\n");
                sb.append("        <codec>").append(escapeXml(info.getAudioCodec())).append("</codec>\n");
                sb.append("      </audio>\n");
            }
            sb.append("    </streamdetails>\n");
            sb.append("  </fileinfo>\n");
        }
    }
}
