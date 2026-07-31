package com.osr.openliststrm.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.common.utils.spring.SpringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * TMDb client helper
 */
@Slf4j
public class TMDbClient {
    // ObjectMapper 线程安全，可在所有 TMDbClient 实例间共享，避免每次刮削创建都 new 一个
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final ObjectMapper mapper;

    public TMDbClient(String apiKey) {
        this.apiKey = apiKey;
        this.mapper = MAPPER;
    }

    public void enrich(MediaInfo info) {
        if (StringUtils.isEmpty(apiKey)) return;

        try {
            String tmdbTitle;
            String type = maybeTV(info) ? "tv" : "movie";
            // get Spring bean that performs TMDb HTTP calls (and provides caching)
            TMDbApiService api = SpringUtils.getBean(TMDbApiService.class);
            tmdbTitle = search(type, info, api);
            if (StringUtils.isNotEmpty(tmdbTitle)) {
                info.setTitle(tmdbTitle);
            }

            // TV: 获取当前季的集详情
            if (maybeTV(info) && info.getSeason() != null && info.getTmdbId() != null) {
                try {
                    enrichEpisodeDetails(info, api);
                } catch (Exception e) {
                    log.warn("enrichEpisodeDetails failed for tvId={}, season={}: {}",
                            info.getTmdbId(), info.getSeason(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("TMDb enrich error", e);
        }
    }

    /**
     * 已知 tmdbId 时直接拉取详情，跳过模糊搜索匹配。
     * 用于"重新刮削"等场景：搜索容易在续集/重制版/同名作品之间选错，
     * 而已入库的 tmdbId 是此前（可能经过人工修正）确定的结果，应优先复用。
     */
    public void enrichByTmdbId(MediaInfo info, String type, String tmdbId) {
        if (StringUtils.isEmpty(apiKey) || StringUtils.isBlank(tmdbId)) return;
        String resolvedType = StringUtils.isNotBlank(type) ? type : (maybeTV(info) ? "tv" : "movie");

        try {
            int id = Integer.parseInt(tmdbId.trim());
            TMDbApiService api = SpringUtils.getBean(TMDbApiService.class);
            info.setTmdbId(String.valueOf(id));

            JsonNode d = mapper.readTree(api.getDetails(apiKey, resolvedType, id));
            if (d != null) {
                info.setYear(getYearSafe(d, resolvedType));
                String best = getBestTitle(resolvedType, d, id, api);
                if (StringUtils.isNotEmpty(best)) {
                    info.setTitle(best);
                }
                applyDetails(resolvedType, id, d, info, api);
            } else {
                log.warn("按 tmdbId 拉取详情为空，回退为搜索匹配：type={}, tmdbId={}", resolvedType, tmdbId);
                enrich(info);
                return;
            }

            if ("tv".equals(resolvedType) && info.getSeason() != null) {
                try {
                    enrichEpisodeDetails(info, api);
                } catch (Exception e) {
                    log.warn("enrichEpisodeDetails failed for tvId={}, season={}: {}",
                            tmdbId, info.getSeason(), e.getMessage());
                }
            }
        } catch (NumberFormatException e) {
            log.warn("tmdbId 格式非法，回退为搜索匹配：{}", tmdbId);
            enrich(info);
        } catch (Exception e) {
            log.error("TMDb enrichByTmdbId error, tmdbId={}", tmdbId, e);
        }
    }

    /**
     * 从 TMDB 获取指定季的集列表，并回填当前集的详情（集标题、播出日期、剧情、tmdbId、评分、导演、编剧）
     */
    private void enrichEpisodeDetails(MediaInfo info, TMDbApiService api) throws IOException {
        int tvId = Integer.parseInt(info.getTmdbId());
        int seasonNum = Integer.parseInt(info.getSeason().replaceAll("\\D", ""));
        String epJson = api.getSeasonEpisodes(apiKey, tvId, seasonNum);
        if (epJson == null) return;

        JsonNode seasonRoot = mapper.readTree(epJson);

        // 存储季级别数据（air_date、overview、id）供 Season NFO 使用
        {
            java.util.Map<String, Object> seasonMeta = new java.util.HashMap<>();
            if (seasonRoot.hasNonNull("air_date")) {
                seasonMeta.put("air_date", seasonRoot.get("air_date").asText());
            }
            if (seasonRoot.hasNonNull("overview")) {
                seasonMeta.put("overview", seasonRoot.get("overview").asText());
            }
            if (seasonRoot.hasNonNull("id")) {
                seasonMeta.put("id", seasonRoot.get("id").asText());
            }
            if (seasonRoot.hasNonNull("name")) {
                seasonMeta.put("name", seasonRoot.get("name").asText());
            }
            if (!seasonMeta.isEmpty()) {
                info.getMetadata().put("season_details", seasonMeta);
            }
        }

        JsonNode episodes = seasonRoot.path("episodes");
        if (!episodes.isArray() || episodes.isEmpty()) return;

        int currentEpNum = 0;
        if (StringUtils.isNotEmpty(info.getEpisode())) {
            try {
                currentEpNum = Integer.parseInt(info.getEpisode().replaceAll("\\D", ""));
            } catch (NumberFormatException ignored) {
            }
        }

        for (JsonNode ep : episodes) {
            int epNum = ep.path("episode_number").asInt(0);
            if (epNum != currentEpNum) continue;

            // 集标题（优先中文）
            String epName = ep.path("name").asText(null);
            if (StringUtils.isNotEmpty(epName)) {
                info.setEpisodeName(epName);
            }

            // 单集 tmdbId (tv episode id)
            String epTmdbId = ep.path("id").asText(null);
            if (StringUtils.isNotEmpty(epTmdbId)) {
                info.setEpisodeTmdbId(epTmdbId);
            }

            // 播出日期
            String airDate = ep.path("air_date").asText(null);
            if (StringUtils.isNotEmpty(airDate)) {
                info.setEpisodeAiredDate(airDate);
            }

            // 剧情简介
            String overview = ep.path("overview").asText(null);
            if (StringUtils.isNotEmpty(overview)) {
                info.setEpisodePlot(overview);
            }

            // 单集评分
            if (ep.hasNonNull("vote_average")) {
                info.setEpisodeRating(String.format("%.1f", ep.get("vote_average").asDouble(0)));
            }

            // 导演和编剧（从 crew 中提取）
            JsonNode crew = ep.path("crew");
            if (crew.isArray()) {
                StringBuilder directors = new StringBuilder();
                StringBuilder writers = new StringBuilder();
                for (JsonNode c : crew) {
                    String department = c.path("department").asText("");
                    String job = c.path("job").asText("");
                    String name = c.path("name").asText("");
                    if (StringUtils.isEmpty(name)) continue;
                    if ("Directing".equals(department) && "Director".equals(job)) {
                        if (directors.length() > 0) directors.append(", ");
                        directors.append(name);
                    } else if ("Writing".equals(department)) {
                        if (writers.length() > 0) writers.append(", ");
                        writers.append(name);
                    }
                }
                if (directors.length() > 0) info.setEpisodeDirector(directors.toString());
                if (writers.length() > 0) info.setEpisodeWriter(writers.toString());
            }

            // 客串演员（guest_stars）
            JsonNode guestStars = ep.path("guest_stars");
            if (guestStars.isArray() && guestStars.size() > 0) {
                StringBuilder guests = new StringBuilder();
                for (JsonNode gs : guestStars) {
                    String name = gs.path("name").asText("");
                    if (StringUtils.isNotEmpty(name)) {
                        if (guests.length() > 0) guests.append(", ");
                        guests.append(name);
                    }
                }
                if (guests.length() > 0) info.setEpisodeGuestStars(guests.toString());
            }

            // 单集剧照路径
            String stillPath = ep.path("still_path").asText(null);
            if (StringUtils.isNotEmpty(stillPath)) {
                info.setEpisodeStillPath(stillPath);
            }

            break; // 找到当前集后退出
        }
    }

    private boolean maybeTV(MediaInfo info) {
        return info.getSeason() != null ||
                (info.getOriginalTitle() != null && info.getOriginalTitle().matches("(?i).*S\\d{1,2}.*"));
    }

    /**
     * 通用搜索（重构版）
     */
    private String search(String type, MediaInfo info, TMDbApiService api) throws IOException {
        if (StringUtils.isBlank(type) || info == null) return null;

        List<String> candidates = new ArrayList<>();
        candidates.add(info.getTitle());
        candidates.add(info.getOriginalTitle());
        candidates.add(info.getEnglishTitle());
        String title;
        // 逐一尝试（去重 + 过滤空串）
        for (String q : candidates.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList())) {
            log.debug("尝试根据标题查询TMDB：{}", q);
            JsonNode root = mapper.readTree(api.search(apiKey, type, q, info.getYear()));
            title = doSearchOnce(type, info, root, api);
            if (title != null) return title;
        }

        for (String q : candidates.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList())) {
            log.debug("尝试只根据标题查询TMDB，不限定年份：{}", q);
            JsonNode root = mapper.readTree(api.search(apiKey, type, q, null));
            title = doSearchOnce(type, info, root, api);
            if (title != null) return title;
        }

        return null;
    }

    /**
     * 处理 search 返回的 JsonNode（来自 TMDbApiService），在候选结果中打分挑选最佳匹配
     */
    private String doSearchOnce(String type, MediaInfo info, com.fasterxml.jackson.databind.JsonNode root, TMDbApiService api) throws IOException {
        if (root == null) return null;
        JsonNode results = root.path("results");
        log.debug("doSearchOnce: {} results: {}", info.getOriginalName(), results);
        if (!results.isArray() || results.isEmpty()) return null;

        JsonNode picked = pickBestCandidate(type, info, results);

        info.setYear(getYearSafe(picked, type));
        info.setTmdbId(picked.path("id").asText());

        int id = picked.path("id").asInt(-1);
        String best = (id > 0) ? getBestTitle(type, picked, id, api) : null;

        // fetch details to populate genres, original language and origin countries
        if (id > 0) {
            try {
                fetchDetails(type, id, info, api);
            } catch (Exception e) {
                log.warn("fetchDetails failed: {}", e.getMessage());
            }
        }

        return best;
    }

    /**
     * 在多个候选结果中打分挑选最佳匹配，替代"直接取第一条"的粗暴策略。
     * 打分维度：官方中文标题精确匹配（最高权重）、发行年份接近度、TMDb 热度（popularity）、
     * 以及 TMDb 自身相关度排序名次（作为无其他信号时的兜底，保持与旧行为一致）。
     */
    private JsonNode pickBestCandidate(String type, MediaInfo info, JsonNode results) {
        JsonNode best = results.get(0);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < results.size(); i++) {
            JsonNode node = results.get(i);
            double score = scoreCandidate(type, info, node) - i * 0.5; // TMDb 原始相关度名次作为兜底权重
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private double scoreCandidate(String type, MediaInfo info, JsonNode node) {
        double score = 0;

        // 官方中文标题与文件名解析出的标题精确匹配：最强信号
        if (StringUtils.isNotEmpty(info.getOriginalTitle())
                && info.getOriginalTitle().equals(getOfficialChineseTitle(node, type))) {
            score += 100;
        }

        // 发行年份接近度：越接近文件名解析出的年份分越高，差距过大则扣分（防止误选重制版/不同季）
        String targetYear = info.getYear();
        String candidateYear = getYearSafe(node, type);
        if (StringUtils.isNotEmpty(targetYear) && StringUtils.isNotEmpty(candidateYear)) {
            try {
                int diff = Math.abs(Integer.parseInt(targetYear) - Integer.parseInt(candidateYear));
                if (diff == 0) score += 30;
                else if (diff == 1) score += 15;
                else if (diff <= 3) score += 5;
                else score -= 10;
            } catch (NumberFormatException ignored) {
            }
        }

        // TMDb 热度：对数压缩，避免头部大热门作品的 popularity 数值压过标题/年份信号
        double popularity = node.path("popularity").asDouble(0);
        score += Math.log1p(Math.max(popularity, 0)) * 2;

        return score;
    }

    private void fetchDetails(String type, int id, MediaInfo info, TMDbApiService api) throws IOException {
        JsonNode d = mapper.readTree(api.getDetails(apiKey, type, id));
        applyDetails(type, id, d, info, api);
    }

    /**
     * 将 /movie(tv)/{id} 详情响应回填到 info（genres、语言、地区、海报、分级等）。
     * 从 fetchDetails 中拆出，便于已持有详情 JsonNode 时直接复用，避免重复请求。
     */
    private void applyDetails(String type, int id, JsonNode d, MediaInfo info, TMDbApiService api) {
        if (d == null) return;
        info.getMetadata().put("details", d);

        // genres -> ids
        JsonNode genres = d.path("genres");
        if (genres.isArray()) {
            info.getGenreIds().clear();
            for (JsonNode g : genres) {
                if (g.has("id")) info.getGenreIds().add(String.valueOf(g.get("id").asInt()));
            }
        }

        // original language
        if (d.hasNonNull("original_language")) {
            info.setOriginalLanguage(d.get("original_language").asText());
        }

        // origin countries
        extractOriginCountries(d, type, info);

        // images/external_ids/content_ratings(或release_dates)/season_images 相互独立，
        // 用虚拟线程并发拉取，避免最多5次请求串行叠加耗时（各自的 metadata 写入已加锁，见 fetchAndStore）
        String label = "tv".equals(type) ? "剧集" : "电影";
        List<Runnable> tasks = new ArrayList<>();
        tasks.add(() -> fetchAndStore(api, info, "images", label, () ->
                "tv".equals(type) ? api.getTvImages(apiKey, id) : api.getMovieImages(apiKey, id)));
        tasks.add(() -> fetchAndStore(api, info, "external_ids", label, () -> api.getExternalIds(apiKey, type, id)));
        if ("tv".equals(type)) {
            tasks.add(() -> fetchAndStore(api, info, "content_ratings", label, () -> api.getTvContentRatings(apiKey, id)));
            tasks.add(() -> fetchSeasonImagesIfNeeded(api, info, id));
        } else {
            tasks.add(() -> fetchAndStore(api, info, "release_dates", label, () -> api.getMovieReleaseDates(apiKey, id)));
        }
        runConcurrently(tasks);
    }

    /**
     * 并发执行一组互不依赖的任务并等待全部完成。
     */
    private static void runConcurrently(List<Runnable> tasks) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = tasks.stream()
                    .map(t -> CompletableFuture.runAsync(t, executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    private void extractOriginCountries(JsonNode d, String type, MediaInfo info) {
        if ("tv".equals(type)) {
            JsonNode oc = d.path("origin_country");
            if (oc.isArray()) {
                info.getOriginCountries().clear();
                for (JsonNode c : oc) info.getOriginCountries().add(c.asText());
            }
        } else {
            JsonNode pcs = d.path("production_countries");
            if (pcs.isArray()) {
                info.getOriginCountries().clear();
                for (JsonNode pc : pcs) {
                    if (pc.has("iso_3166_1")) info.getOriginCountries().add(pc.get("iso_3166_1").asText());
                }
            }
        }
    }

    /**
     * 通用元数据获取：调用 API 获取 JSON 并存入 metadata，自动处理异常和日志。
     */
    private void fetchAndStore(TMDbApiService api, MediaInfo info, String key, String label,
                               java.util.function.Supplier<String> apiCall) {
        try {
            String json = apiCall.get();
            if (json != null) {
                JsonNode node = mapper.readTree(json);
                // metadata 是普通 HashMap，applyDetails 中多个 fetchAndStore 任务并发执行，写入需加锁
                synchronized (info.getMetadata()) {
                    info.getMetadata().put(key, node);
                }
                log.debug("获取{} {} 成功: {}", label, key, node);
            }
        } catch (Exception e) {
            log.warn("获取{} {} 失败: {}", label, key, e.getMessage());
        }
    }

    private void fetchSeasonImagesIfNeeded(TMDbApiService api, MediaInfo info, int tvId) {
        if (info.getSeason() == null) return;
        try {
            int seasonNum = Integer.parseInt(info.getSeason().replaceAll("\\D", ""));
            String seasonImagesJson = api.getTvSeasonImages(apiKey, tvId, seasonNum);
            if (seasonImagesJson != null) {
                JsonNode seasonImages = mapper.readTree(seasonImagesJson);
                synchronized (info.getMetadata()) {
                    info.getMetadata().put("season_images", seasonImages);
                }
                log.debug("获取季 images 成功: tvId={}, season={}, posters={}",
                        tvId, seasonNum, seasonImages.path("posters").size());
            }
        } catch (Exception e) {
            log.warn("获取季 images 失败: tvId={}, error={}", tvId, e.getMessage());
        }
    }

    private String getBestTitle(String type, JsonNode result, int id, TMDbApiService api) throws IOException {
        String title = getOfficialChineseTitle(result, type);
        if (StringUtils.isNotEmpty(title)) return title;

        title = fetchChineseAlias(type, id, api);
        if (StringUtils.isNotEmpty(title)) return title;

        return fallbackTitle(result, type);
    }

    private String getOfficialChineseTitle(JsonNode result, String type) {
        String name = type.equals("movie") ? result.path("title").asText() : result.path("name").asText();
        if (isChinese(name)) {
            return name;
        }
        return null;
    }

    private String fetchChineseAlias(String type, int id, TMDbApiService api) throws IOException {
        JsonNode root = mapper.readTree(api.getAlternativeTitles(apiKey, type, id));
        if (root == null) return null;
        log.debug("fetchChineseAlias: {}", root);

        JsonNode titles = type.equals("movie") ? root.get("titles") : root.get("results");
        if (titles != null) {
            for (JsonNode t : titles) {
                if (t.has("iso_3166_1") && "CN".equals(t.get("iso_3166_1").asText())) {
                    String title = t.has("title") ? t.get("title").asText() :
                            t.has("name") ? t.get("name").asText() : null;
                    if (isChinese(title)) return title;
                }
            }
        }
        return null;
    }

    private String fallbackTitle(JsonNode result, String type) {
        return type.equals("movie") ? result.path("title").asText()
                : result.path("name").asText();
    }

    private String getYearSafe(JsonNode result, String type) {
        String dateField = type.equals("movie") ? "release_date" : "first_air_date";
        if (result.hasNonNull(dateField)) {
            String date = result.get(dateField).asText();
            if (date != null && date.length() >= 4) {
                return date.substring(0, 4);
            }
        }
        return "";
    }

    private boolean isChinese(String text) {
        return text != null && text.matches(".*[\\u4E00-\\u9FFF].*");
    }
}
