package com.osr.openliststrm.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.rename.model.MediaInfo;

import java.util.Map;

import static com.osr.openliststrm.scrape.NfoXmlBuilder.*;

/**
 * 季（Season）NFO 构建策略。
 * 根节点: &lt;season&gt;
 */
public class SeasonNfoBuilder implements NfoTypeStrategy {

    @Override
    public String buildNfo(MediaInfo info) {
        return wrapXml("season", sb -> buildBody(sb, info));
    }

    private void buildBody(StringBuilder sb, MediaInfo info) {
        int seasonNum = parseSeasonNumber(info.getSeason());

        // 季标题：优先使用 TMDb 返回的季名称
        Object sdObj = info.getMetadata() != null ? info.getMetadata().get("season_details") : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> seasonMeta = (sdObj instanceof Map)
                ? (Map<String, Object>) sdObj : null;

        String seasonName = null;
        if (seasonMeta != null && seasonMeta.get("name") instanceof String n && StringUtils.isNotBlank(n)) {
            seasonName = n;
        }
        appendTag(sb, "title", StringUtils.isNotBlank(seasonName) ? seasonName : "第 " + seasonNum + " 季");
        appendTag(sb, "seasonnumber", String.valueOf(seasonNum));

        // tvshowid 关联
        if (StringUtils.isNotBlank(info.getTmdbId())) {
            appendTag(sb, "tvshowid", info.getTmdbId());
        }

        // 季级别的 uniqueid：使用季的 TMDb ID
        String seasonTmdbId = null;
        if (seasonMeta != null && seasonMeta.get("id") instanceof String sid) {
            seasonTmdbId = sid;
        }
        if (StringUtils.isNotBlank(seasonTmdbId)) {
            appendUniqueid(sb, seasonTmdbId, "tmdb", "true");
        }

        // 年份：从季的首播日期提取
        String seasonAirDate = null;
        if (seasonMeta != null && seasonMeta.get("air_date") instanceof String ad) {
            seasonAirDate = ad;
        }
        if (StringUtils.isNotBlank(seasonAirDate) && seasonAirDate.length() >= 4) {
            appendTag(sb, "year", seasonAirDate.substring(0, 4));
        }

        // 首播日期：使用季的真实首播日期
        if (StringUtils.isNotBlank(seasonAirDate)) {
            appendTag(sb, "premiered", seasonAirDate);
        }

        // 剧情：使用季的独立概述
        String seasonPlot = null;
        if (seasonMeta != null && seasonMeta.get("overview") instanceof String so) {
            seasonPlot = so;
        }
        if (StringUtils.isBlank(seasonPlot)) {
            // 回退：使用剧集整体概述
            JsonNode details = getDetails(info);
            if (details != null && details.has("overview")) {
                seasonPlot = details.get("overview").asText();
            }
        }
        appendCDataTag(sb, "plot", StringUtils.isNotBlank(seasonPlot) ? truncate(seasonPlot, 500) : null);

        // 季级别图片引用
        JsonNode seasonImages = getSeasonImages(info);
        if (seasonImages != null) {
            JsonNode posters = seasonImages.path("posters");
            if (posters.isArray() && posters.size() > 0) {
                String posterPath = posters.get(0).path("file_path").asText(null);
                if (StringUtils.isNotBlank(posterPath)) {
                    sb.append("  <thumb aspect=\"poster\">").append(TMDb_IMG_BASE).append(escapeXml(posterPath)).append("</thumb>\n");
                }
            }
        }
    }
}
