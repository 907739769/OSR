package com.osr.openliststrm.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.rename.model.MediaInfo;

import static com.osr.openliststrm.scrape.NfoXmlBuilder.*;

/**
 * 剧集（TV Show）NFO 构建策略。
 * 根节点: &lt;tvshow&gt;
 */
public class TvShowNfoBuilder implements NfoTypeStrategy {

    @Override
    public String buildNfo(MediaInfo info) {
        JsonNode details = getDetails(info);
        JsonNode externalIds = getExternalIds(info);
        return wrapXml("tvshow", sb -> buildBody(sb, info, details, externalIds));
    }

    private void buildBody(StringBuilder sb, MediaInfo info, JsonNode details, JsonNode externalIds) {
        // 标题优先取 TMDb 接口返回值
        String tvTitle = details != null && details.hasNonNull("name") ? details.get("name").asText() : info.getTitle();
        String tvOriginalTitle = details != null && details.hasNonNull("original_name") ? details.get("original_name").asText() : tvTitle;
        appendTag(sb, "title", tvTitle);
        appendTag(sb, "originaltitle", tvOriginalTitle);
        appendTag(sb, "showtitle", tvTitle);

        // TMDB ID + IMDb ID
        String imdbId = extractImdbId(details, externalIds);
        appendIdBlock(sb, info.getTmdbId(), imdbId);

        // TVDb ID
        if (externalIds != null && externalIds.hasNonNull("tvdb_id")) {
            String tvdbId = externalIds.get("tvdb_id").asText();
            if (StringUtils.isNotBlank(tvdbId)) {
                appendUniqueid(sb, tvdbId, "tvdb", "false");
            }
        }

        // 年份
        String firstAirDate = null;
        if (details != null && details.has("first_air_date")) {
            firstAirDate = details.get("first_air_date").asText();
            if (firstAirDate != null && firstAirDate.length() >= 4) {
                appendTag(sb, "year", firstAirDate.substring(0, 4));
            }
        }

        // 首播日期
        appendTag(sb, "premiered", firstAirDate);

        // 剧集状态
        if (details != null && details.has("status")) {
            String status = details.get("status").asText();
            if (StringUtils.isNotBlank(status)) {
                appendTag(sb, "status", status);
            }
        }

        // 多源评分
        appendRatings(sb, details, imdbId);

        // 内容分级 (TV-14, TV-MA 等)
        String tvCertification = extractTvCertification(info);
        if (StringUtils.isNotBlank(tvCertification)) {
            appendTag(sb, "mpaa", tvCertification);
        }

        // 简介
        String overview = details != null && details.has("overview") ? details.get("overview").asText() : null;
        appendCDataTag(sb, "plot", overview);
        appendCDataTag(sb, "outline", truncate(overview, 200));

        // tagline
        appendTag(sb, "tagline", details != null && details.has("tagline") ? details.get("tagline").asText() : null);

        // 季数、集数
        if (details != null) {
            if (details.has("number_of_seasons")) {
                appendTag(sb, "season", String.valueOf(details.get("number_of_seasons").asInt()));
            }
            if (details.has("number_of_episodes")) {
                appendTag(sb, "episode", String.valueOf(details.get("number_of_episodes").asInt()));
            }
        }

        // 类型、国家、播出网络
        appendGenres(sb, details, "genres");
        if (details != null && details.has("origin_country")) {
            JsonNode oc = details.get("origin_country");
            for (JsonNode c : oc) {
                appendTag(sb, "country", c.asText());
            }
        }
        // studio 使用播出网络（networks）而非制作公司
        if (details != null && details.has("networks") && details.get("networks").isArray() && !details.get("networks").isEmpty()) {
            appendStudios(sb, details, "networks", "name");
        } else {
            appendStudios(sb, details, "production_companies", "name");
        }
        appendActorsStructured(sb, details);

        // 标签 (keywords)
        appendKeywords(sb, details);

        // 图片引用 (thumb)
        appendThumbs(sb, details);
    }
}
