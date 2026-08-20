package com.osr.openliststrm.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.osr.common.utils.StringUtils;

import java.util.function.Consumer;

/**
 * NFO XML 标签构建工具类，封装所有类型共享的 XML 构建方法。
 */
public final class NfoXmlBuilder {

    public static final String TMDb_IMG_BASE = "https://image.tmdb.org/t/p/original";

    private NfoXmlBuilder() {}

    // ==================== XML 骨架 ====================

    /**
     * 生成 "&lt;?xml ...?&gt; + 根标签 + body + 闭合根标签" 的完整 NFO 文档，
     * 四种 NfoTypeStrategy 实现共用同一套外壳，避免各自重复声明。
     */
    public static String wrapXml(String rootTag, Consumer<StringBuilder> body) {
        StringBuilder sb = new StringBuilder();
        sb.append(xmlHeader());
        sb.append("<").append(rootTag).append(">\n");
        body.accept(sb);
        sb.append("</").append(rootTag).append(">\n");
        return sb.toString();
    }

    /**
     * 输出 tmdbid + imdbid + 对应的 uniqueid 节点。Movie/TvShow 两种类型的写法完全一致，故抽取共用。
     */
    public static void appendIdBlock(StringBuilder sb, String tmdbId, String imdbId) {
        appendTag(sb, "tmdbid", tmdbId);
        if (StringUtils.isNotBlank(imdbId)) {
            appendTag(sb, "imdbid", imdbId);
            appendUniqueid(sb, imdbId, "imdb", "true");
        }
        appendUniqueid(sb, tmdbId, "tmdb", imdbId == null ? "true" : "false");
    }

    // ==================== 基础标签 ====================

    public static void appendTag(StringBuilder sb, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            sb.append("  <").append(name).append(">").append(escapeXml(value)).append("</").append(name).append(">\n");
        }
    }

    public static void appendTag(StringBuilder sb, String name, String value, String indent) {
        if (StringUtils.isNotBlank(value)) {
            sb.append(indent).append("<").append(name).append(">").append(escapeXml(value)).append("</").append(name).append(">\n");
        }
    }

    public static void appendTag(StringBuilder sb, String name, String value, String type, String defaultFlag) {
        if (StringUtils.isNotBlank(value)) {
            sb.append("  <").append(name).append(" type=\"").append(type).append("\" default=\"").append(defaultFlag).append("\">")
                    .append(escapeXml(value)).append("</").append(name).append(">\n");
        }
    }

    /**
     * 写入 CDATA 包裹的标签，适用于 plot/outline 等可能含特殊字符的长文本。
     * CDATA 内部无需转义 XML 实体，但需要处理内容中出现的 "]]>" 序列。
     */
    public static void appendCDataTag(StringBuilder sb, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            String safe = value.replace("]]>", "]]><![CDATA[>");
            sb.append("  <").append(name).append("><![CDATA[").append(safe).append("]]></").append(name).append(">\n");
        }
    }

    public static void appendUniqueid(StringBuilder sb, String value, String type, String defaultFlag) {
        if (StringUtils.isNotBlank(value)) {
            appendTag(sb, "uniqueid", value, type, defaultFlag);
        }
    }

    // ==================== 复合标签 ====================

    public static void appendRatings(StringBuilder sb, JsonNode details, String imdbId) {
        if (details == null) return;
        boolean hasTmdbRating = details.has("vote_average") && details.get("vote_average").asDouble(0) > 0;
        boolean hasImdb = StringUtils.isNotBlank(imdbId);
        if (!hasTmdbRating && !hasImdb) return;

        sb.append("  <ratings>\n");
        if (hasTmdbRating) {
            sb.append("    <rating name=\"themoviedb\" max=\"10\" default=\"true\">\n");
            appendTag(sb, "value", String.format("%.1f", details.get("vote_average").asDouble(0)), "      ");
            if (details.has("vote_count")) {
                appendTag(sb, "votes", String.valueOf(details.get("vote_count").asInt()), "      ");
            }
            sb.append("    </rating>\n");
        }
        sb.append("  </ratings>\n");
    }

    public static void appendGenres(StringBuilder sb, JsonNode details, String key) {
        if (details != null && details.has(key)) {
            for (JsonNode g : details.get(key)) {
                appendTag(sb, "genre", g.get("name").asText());
            }
        }
    }

    public static void appendCountries(StringBuilder sb, JsonNode details, String key, String nameField) {
        if (details != null && details.has(key)) {
            JsonNode countries = details.get(key);
            for (JsonNode c : countries) {
                appendTag(sb, "country", c.get(nameField).asText());
            }
        }
    }

    public static void appendStudios(StringBuilder sb, JsonNode details, String key, String nameField) {
        if (details != null && details.has(key)) {
            JsonNode companies = details.get(key);
            for (JsonNode c : companies) {
                appendTag(sb, "studio", c.get(nameField).asText());
            }
        }
    }

    public static void appendDirectors(StringBuilder sb, JsonNode details) {
        if (details != null && details.has("credits")) {
            JsonNode credits = details.get("credits");
            if (credits.has("crew")) {
                for (JsonNode member : credits.get("crew")) {
                    String job = member.path("job").asText("");
                    if ("Director".equals(job)) {
                        appendTag(sb, "director", member.get("name").asText());
                    }
                }
            }
        }
    }

    public static void appendWriters(StringBuilder sb, JsonNode details) {
        if (details == null || !details.has("credits")) return;
        JsonNode credits = details.get("credits");
        if (!credits.has("crew")) return;
        for (JsonNode member : credits.get("crew")) {
            String department = member.path("department").asText("");
            if ("Writing".equals(department)) {
                String name = member.path("name").asText(null);
                if (StringUtils.isNotBlank(name)) {
                    appendTag(sb, "writer", name);
                }
            }
        }
    }

    /**
     * 结构化 actor 节点：包含 name、role、thumb
     */
    public static void appendActorsStructured(StringBuilder sb, JsonNode details) {
        if (details == null || !details.has("credits")) return;
        JsonNode credits = details.get("credits");
        if (!credits.has("cast")) return;
        int count = 0;
        for (JsonNode member : credits.get("cast")) {
            if (count >= 20) break;
            String name = member.path("name").asText(null);
            String role = member.path("character").asText(null);
            String profilePath = member.path("profile_path").asText(null);
            if (StringUtils.isBlank(name)) continue;
            sb.append("  <actor>\n");
            appendTag(sb, "name", name, "    ");
            appendTag(sb, "role", role, "    ");
            if (StringUtils.isNotBlank(profilePath)) {
                appendTag(sb, "thumb", TMDb_IMG_BASE + profilePath, "    ");
            }
            sb.append("  </actor>\n");
            count++;
        }
    }

    public static void appendKeywords(StringBuilder sb, JsonNode details) {
        if (details == null) return;
        JsonNode keywords = null;
        if (details.has("keywords") && details.get("keywords").has("keywords")) {
            keywords = details.get("keywords").get("keywords");
        } else if (details.has("keywords") && details.get("keywords").isArray()) {
            keywords = details.get("keywords");
        }
        if (keywords != null && keywords.isArray()) {
            int count = 0;
            for (JsonNode kw : keywords) {
                if (count >= 10) break;
                String name = kw.path("name").asText(null);
                if (StringUtils.isNotBlank(name)) {
                    appendTag(sb, "tag", name);
                    count++;
                }
            }
        }
    }

    public static void appendThumbs(StringBuilder sb, JsonNode details) {
        if (details == null) return;
        // poster
        String posterPath = details.path("poster_path").asText(null);
        if (StringUtils.isNotBlank(posterPath)) {
            sb.append("  <thumb aspect=\"poster\" preview=\"").append(TMDb_IMG_BASE).append(escapeXml(posterPath)).append("\">")
                    .append(TMDb_IMG_BASE).append(escapeXml(posterPath)).append("</thumb>\n");
        }
        // fanart / backdrop
        String backdropPath = details.path("backdrop_path").asText(null);
        if (StringUtils.isNotBlank(backdropPath)) {
            sb.append("  <fanart>\n");
            sb.append("    <thumb preview=\"").append(TMDb_IMG_BASE).append(escapeXml(backdropPath)).append("\">")
                    .append(TMDb_IMG_BASE).append(escapeXml(backdropPath)).append("</thumb>\n");
            sb.append("  </fanart>\n");
        }
    }

    // ==================== 数据提取 ====================

    /**
     * 作品标题：<b>优先取 {@code info.title}</b>，TMDb 详情里的字段只作兜底。
     * <p>
     * 顺序不能反过来。{@code details} 是按 {@code openlist.tmdb.metadata.language}（默认 zh-CN）
     * 请求的，但 TMDb 缺中文翻译时 name/title 会<b>直接退回英文</b>（Apple TV+ / Netflix 的新剧、
     * 冷门纪录片、动画常见）；而 {@code info.title} 是 {@code TMDbClient#getBestTitle} 的结果——
     * 官方中文名 → alternative_titles 的中文别名 → 英文，恰好把这种情况兜住了。
     * 反过来取的话，重命名出来的目录是「足球教练 (2020)」，nfo 里写的却是「Ted Lasso」，
     * 而媒体库显示的是 nfo 里那一个：用户看到的就是中文目录配英文剧名，比两边都英文更让人困惑。
     * </p>
     * <p>
     * 注意只有<b>作品标题</b>该这么取。{@code originaltitle} 走 original_name/original_title，
     * 那本来就该是原语言标题，不参与中文化。
     * </p>
     *
     * @param detailsField 详情响应里的标题字段名：剧集为 {@code name}，电影为 {@code title}
     */
    public static String preferredTitle(com.osr.openliststrm.rename.model.MediaInfo info,
                                        JsonNode details, String detailsField) {
        if (info != null && StringUtils.isNotBlank(info.getTitle())) {
            return info.getTitle();
        }
        return details != null && details.hasNonNull(detailsField) ? details.get(detailsField).asText() : null;
    }

    public static JsonNode getDetails(com.osr.openliststrm.rename.model.MediaInfo info) {
        if (info.getMetadata() == null) return null;
        Object details = info.getMetadata().get("details");
        if (details instanceof JsonNode) {
            return (JsonNode) details;
        }
        return null;
    }

    public static JsonNode getExternalIds(com.osr.openliststrm.rename.model.MediaInfo info) {
        if (info.getMetadata() == null) return null;
        Object extIds = info.getMetadata().get("external_ids");
        if (extIds instanceof JsonNode) {
            return (JsonNode) extIds;
        }
        return null;
    }

    public static JsonNode getSeasonImages(com.osr.openliststrm.rename.model.MediaInfo info) {
        if (info.getMetadata() == null) return null;
        Object si = info.getMetadata().get("season_images");
        if (si instanceof JsonNode) {
            return (JsonNode) si;
        }
        return null;
    }

    /**
     * 提取 IMDb ID：优先取 details.imdb_id（电影详情接口才有此字段），否则回退到 external_ids.imdb_id。
     */
    public static String extractImdbId(JsonNode details, JsonNode externalIds) {
        if (details != null && details.hasNonNull("imdb_id")) {
            return details.get("imdb_id").asText();
        }
        if (externalIds != null && externalIds.hasNonNull("imdb_id")) {
            return externalIds.get("imdb_id").asText();
        }
        return null;
    }

    /**
     * 提取电影内容分级（MPAA 等）
     */
    public static String extractCertification(com.osr.openliststrm.rename.model.MediaInfo info) {
        try {
            Object rdObj = info.getMetadata().get("release_dates");
            if (!(rdObj instanceof JsonNode)) return null;
            JsonNode rd = (JsonNode) rdObj;
            JsonNode results = rd.path("results");
            if (!results.isArray()) return null;
            String[] priorityCountries = {"CN", "US"};
            for (String country : priorityCountries) {
                for (JsonNode r : results) {
                    if (country.equals(r.path("iso_3166_1").asText(""))) {
                        JsonNode dates = r.path("release_dates");
                        if (dates.isArray()) {
                            for (JsonNode d : dates) {
                                String cert = d.path("certification").asText("");
                                if (StringUtils.isNotBlank(cert)) return cert;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 提取电视剧内容分级（TV-14, TV-MA 等）
     */
    public static String extractTvCertification(com.osr.openliststrm.rename.model.MediaInfo info) {
        try {
            if (info.getMetadata() == null) return null;
            Object crObj = info.getMetadata().get("content_ratings");
            if (!(crObj instanceof JsonNode)) return null;
            JsonNode cr = (JsonNode) crObj;
            JsonNode results = cr.path("results");
            if (!results.isArray()) return null;
            String[] priorityCountries = {"CN", "US"};
            for (String country : priorityCountries) {
                for (JsonNode r : results) {
                    if (country.equals(r.path("iso_3166_1").asText(""))) {
                        String rating = r.path("rating").asText("");
                        if (StringUtils.isNotBlank(rating)) return rating;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public static String extractTrailerUrl(JsonNode details) {
        if (details == null || !details.has("videos")) return null;
        JsonNode videos = details.get("videos");
        if (!videos.has("results")) return null;
        for (JsonNode v : videos.get("results")) {
            if ("YouTube".equals(v.path("site").asText("")) && "Trailer".equals(v.path("type").asText(""))) {
                return "plugin://plugin.video.youtube/play/?video_id=" + v.get("key").asText();
            }
        }
        return null;
    }

    // ==================== 工具方法 ====================

    public static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    public static int parseSeasonNumber(String season) {
        if (StringUtils.isBlank(season)) return 0;
        try {
            return Integer.parseInt(season.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int parseEpisodeNumber(String episode) {
        if (StringUtils.isBlank(episode)) return 0;
        try {
            return Integer.parseInt(episode.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String parseResolutionWidth(String resolution) {
        if (resolution == null) return "";
        String lower = resolution.toLowerCase();
        if (lower.contains("2160") || lower.contains("4k") || lower.contains("uhd")) return "3840";
        if (lower.contains("1080")) return "1920";
        if (lower.contains("720")) return "1280";
        if (lower.contains("480")) return "854";
        if (lower.contains("576")) return "1024";
        return "";
    }

    public static String parseResolutionHeight(String resolution) {
        if (resolution == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})").matcher(resolution);
        if (m.find()) return m.group(1);
        return "";
    }

    public static String xmlHeader() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n";
    }
}
