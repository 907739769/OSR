package com.osr.openliststrm.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.Threads;
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

    /** 包含关系判定时，较短一方的最小长度（拉丁）——防止 "Up"、"It" 被任意长标题包含 */
    private static final int MIN_CONTAINS_LENGTH_LATIN = 4;

    /** 同上（CJK）。单个汉字/假名的信息量远高于拉丁字母，两字（「三体」）已足够可辨 */
    private static final int MIN_CONTAINS_LENGTH_CJK = 2;

    /** {@link #titleMatchLevel} 的取值：归一化后逐字相等。最强的标题信号，在 {@link #rankCandidates} 里自成一档 */
    private static final int TITLE_MATCH_EXACT = 2;

    /** {@link #titleMatchLevel} 的取值：一方包含另一方。它宽松得多，不与全等争同一个名次 */
    private static final int TITLE_MATCH_CONTAINS = 1;

    /**
     * 年份被视为"接近"的最大偏差。与 {@code SubscriptionMatcher#MOVIE_YEAR_TOLERANCE} 同口径：
     * 电影节首映 vs 正式公映、年末跨年上映会让同一部作品在不同来源差一年。
     */
    private static final int YEAR_CLOSE_TOLERANCE = 1;

    /**
     * 每次搜索最多检验前几名候选。冠军被否决时继续往下看，而不是整批放弃——
     * 打分只负责排序，采纳与否由门槛和反证决定，而正确答案常常只是打分上的次席
     * （中文作品拿英文名去搜时，它的 name/original_name 全是中文，一分标题分都拿不到）。
     * 取 3 是因为每个被检验的候选最坏会多发一次详情请求，再往后收益迅速衰减。
     */
    private static final int MAX_CANDIDATES_EXAMINED = 3;

    /**
     * 集号超过候选剧总集数的多少倍才判定为「矛盾」。留一倍余量的理由见
     * {@link #episodeCountContradicts}。
     */
    private static final int EPISODE_OVERFLOW_FACTOR = 2;

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
                    log.warn("补全分集详情失败：tvId={}, season={}：{}",
                            info.getTmdbId(), info.getSeason(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("TMDb 信息补全异常", e);
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
                    log.warn("补全分集详情失败：tvId={}, season={}：{}",
                            tmdbId, info.getSeason(), e.getMessage());
                }
            }
        } catch (NumberFormatException e) {
            log.warn("tmdbId 格式非法，回退为搜索匹配：{}", tmdbId);
            enrich(info);
        } catch (Exception e) {
            log.error("按 tmdbId 补全 TMDb 信息异常：tmdbId={}", tmdbId, e);
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
     * 通用搜索：按标题候选（title → originalTitle → englishTitle）逐个尝试。
     * <p>
     * <b>外层循环换标题，内层才降级年份</b>——标题是强信号，年份是弱信号，
     * 该先放宽弱信号而不是先换强信号。原实现是反的（先拿所有标题带年份各试一遍，
     * 再拿所有标题不带年份各试一遍），于是「次要标题 + 年份碰巧对上」会打败
     * 「主标题 + 年份对不上」：{@code 开始推理吧.The.Truth.S04E18.2026} 这类文件里，
     * 主标题带 2026 搜不到（理由见下），却让 englishTitle "The Truth" 撞上某部 2026 年
     * 首播的同名剧并被直接采纳，整部剧被重命名成另一部作品。
     * </p>
     * <p>
     * <b>剧集一律不带年份搜。</b>{@link TMDbApiService#search} 对剧集用的过滤参数是
     * {@code first_air_date_year}（<b>剧集首播年</b>），而文件名里的年份是发布组随手填的——
     * 可能是首播年，也可能是本季/本集的播出年。两者只在第一季才相等，对 S2 以上的剧集
     * 这个过滤器在构造上就是错的：哪怕发布组填得完全正确，它也一定过滤不到正确答案，
     * 留着只是浪费一次请求外加保留一个误配入口。年份对剧集的甄别作用改由
     * {@link #scoreCandidate} 的年份接近度软打分承担（对同名重启剧反而更稳：
     * 年份对得上加 30 分，对不上时还能靠热度兜底，而硬过滤会把两个版本一起滤掉）。
     * </p>
     * <p>
     * <b>电影保留年份过滤。</b>电影的 {@code primary_release_year} 与文件名里的上映年
     * 是同一个量，语义正确，且能有效区分翻拍；差一年的（电影节首映 vs 正式上映）
     * 由同一标题的下一级「不带年份」兜住。
     * </p>
     * <p>
     * 包内可见而非 private：供 {@code TMDbClientSearchTest} 直接注入 mock 的
     * {@link TMDbApiService} 验证请求顺序，不必为了测一段编排逻辑去搭 Spring 上下文。
     * </p>
     */
    String search(String type, MediaInfo info, TMDbApiService api) throws IOException {
        if (StringUtils.isBlank(type) || info == null) return null;

        List<String> candidates = new ArrayList<>();
        candidates.add(info.getTitle());
        candidates.add(info.getOriginalTitle());
        candidates.add(info.getEnglishTitle());
        List<String> queries = candidates.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());

        // 年份在循环外取一次：doSearchOnce 命中后会把 info.year 改写成 TMDb 的首播/上映年，
        // 循环里现取的话，后续候选会拿上一轮改写过的年份去过滤，行为不可预期
        String year = info.getYear();
        boolean movie = "movie".equals(type);

        for (String q : queries) {
            if (movie && StringUtils.isNotEmpty(year)) {
                log.debug("尝试根据标题+年份查询TMDB：{}（{}）", q, year);
                String title = doSearchOnce(type, info, mapper.readTree(api.search(apiKey, type, q, year)), api);
                if (title != null) return title;
            }
            log.debug("尝试只根据标题查询TMDB，不限定年份：{}", q);
            String title = doSearchOnce(type, info, mapper.readTree(api.search(apiKey, type, q, null)), api);
            if (title != null) return title;
        }

        return null;
    }

    /**
     * 处理 search 返回的 JsonNode（来自 TMDbApiService），在候选结果中挑选并采纳最佳匹配。
     * <p>
     * <b>打分只负责排序，采纳与否由两道独立的检验决定</b>：正面的
     * {@link #hasEnoughEvidence 采纳门槛}（有没有一条独立于 TMDb 相关度排序的正面证据）
     * 与反面的 {@link #episodeCountContradicts 集号反证}（这个候选是否在集数上根本装不下这一集）。
     * 没有门槛的话，「搜到了正确答案」与「搜到了一堆垃圾、靠 TMDb 相关度排序蒙了个第一」
     * 走的是同一条路，后者会把整部剧安静地刮成另一部作品。
     * </p>
     * <p>
     * <b>冠军没通过就往下看次席，而不是整批放弃</b>（最多看
     * {@value #MAX_CANDIDATES_EXAMINED} 个）。正确答案经常只是打分上的第二名——中文作品拿
     * 英文名去搜时，它的 name/original_name 全是中文，一分标题分都拿不到，而一个字面同名的
     * 外国剧能拿满标题分（{@code Perfect World} 事故，详见 {@link #episodeCountContradicts}）。
     * 早先的实现在冠军被拒时直接返回 null，正确答案明明就在同一批结果里却连被检验的机会都没有。
     * </p>
     * <p>
     * 前 {@value #MAX_CANDIDATES_EXAMINED} 个候选全部落空才返回 null，让 {@code search()}
     * 继续降级到下一个候选标题，全部落空则交给 AI 兜底（{@code MediaParser#needsAI} 的判据
     * 正是 tmdbId 为空）。
     * </p>
     */
    private String doSearchOnce(String type, MediaInfo info, com.fasterxml.jackson.databind.JsonNode root, TMDbApiService api) throws IOException {
        if (root == null) return null;
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            log.debug("TMDb 搜索 {} 无命中", info.getOriginalName());
            return null;
        }

        List<JsonNode> ranked = rankCandidates(type, info, results);
        // 只打候选的名字/年份/id，不打整个 results 数组：那是完整响应体（实测平均 1.2KB 一条），
        // 而下面两道检验的日志引用候选靠的正是「第 N 名」+ 名字，数组里其余字段一个都用不上。
        // 放在排序之后：这里的名次与 doSearchOnce 后续日志里的「第 N 名」是同一套编号，
        // 打原始顺序反而对不上号。
        if (log.isDebugEnabled()) {
            log.debug("TMDb 搜索 {} 命中 {} 条，按排序前 {} 名：{}", info.getOriginalName(), results.size(),
                    Math.min(ranked.size(), MAX_CANDIDATES_EXAMINED), describeTop(type, ranked));
        }

        for (int rank = 0; rank < ranked.size() && rank < MAX_CANDIDATES_EXAMINED; rank++) {
            JsonNode picked = ranked.get(rank);

            // 没有可用 id 就没法拉详情（英文规范名也拉不到），此时绝不能写 info：否则 tmdbId/year
            // 被半途改掉，既让后续候选拿着被污染的年份去判断，又会让 needsAI 误以为已经识别出来了
            int id = picked.path("id").asInt(-1);
            if (id <= 0) {
                log.debug("TMDb 候选缺少 id，跳过：{}", info.getOriginalName());
                continue;
            }

            if (!hasEnoughEvidence(type, info, picked, id, api)) {
                // 降 DEBUG 并写明「还有后手」：这是三级回退链的<b>中间步骤</b>，不是终态。
                // 罗马音命名的文件必然走到这里（TMDb 的三个标题里没有罗马音这一种），
                // 随后由下一个候选标题或 AI 兜底接住，实测 8 集全部刮削成功。
                // 原先打在 INFO、话说到「跳过」为止，读起来就是刮削失败——读日志的人会去
                // 追查一个并不存在的故障。真正落空时紧跟着的 MediaParser「使用AI识别」才是那个信号。
                log.debug("TMDb 结果证据不足，不采纳该候选（继续看下一候选，全部落空则换标题重搜、最终由 AI 兜底）：{} "
                                + "—— 第 {} 名候选是「{}（{}）」，"
                                + "但与解析出的标题「{}」既不匹配（含英文规范名）、年份也不接近（解析年份 {}）",
                        info.getOriginalName(), rank + 1, describeCandidate(type, picked),
                        getYearSafe(picked, type), firstNonBlankTitle(info), info.getYear());
                continue;
            }

            if (episodeCountContradicts(type, info, id, api)) {
                log.info("TMDb 候选被集数反证否决，继续看下一位：{} —— 第 {} 名候选「{}（{}）」全剧总集数容不下第 {} 集",
                        info.getOriginalName(), rank + 1, describeCandidate(type, picked),
                        getYearSafe(picked, type), info.getEpisode());
                continue;
            }

            info.setYear(getYearSafe(picked, type));
            info.setTmdbId(picked.path("id").asText());

            String best = getBestTitle(type, picked, id, api);

            // fetch details to populate genres, original language and origin countries
            try {
                fetchDetails(type, id, info, api);
            } catch (Exception e) {
                log.warn("拉取 TMDb 详情失败：{}", e.getMessage());
            }

            return best;
        }

        return null;
    }

    /**
     * 集号反证：候选剧的<b>全剧总集数</b>连解析出的集号都装不下，那它一定不是这部作品。
     * <p>
     * 这是唯一一条既不依赖标题、也不依赖文件名年份的证据，专治「字面同名的另一部作品」：
     * {@code Perfect.World.S01E282.2021} 里，TMDb 上 2000 年那部英国喜剧 {@code Perfect World}
     * 的 {@code original_name} 与解析标题<b>逐字相等</b>（+100 分），而正确答案国产动画《完美世界》
     * 的 name/original_name 都是中文、拿不到任何标题分，只靠年份吻合的加分——打分上毫无胜算。
     * 于是整部剧被刮成一部 6 集英剧，年份被改写成 2000、{@code origin_country=GB} 还把它分到了「欧美剧」。
     * 集号这一维是文件名里最硬的信息（发布组不会把第 282 集写成 282），一票就能否掉。
     * </p>
     * <p>
     * <b>为什么是「超过总集数的 {@value #EPISODE_OVERFLOW_FACTOR} 倍」而不是简单的「超过总集数」</b>：
     * 集号有三套且彼此不一致（见 AGENTS.md「集号有三套」一节）——发布组用绝对集号、TMDb 按季编号时，
     * {@code One.Piece.S01E1173} 这种命名的集号本来就可能略微超出 TMDb 记录的总集数；
     * 新集刚播出时 TMDb 数据滞后一两集也是常态。留一倍余量，只否掉「差着数量级」的情况
     * （282 vs 6），把误否的代价（该文件不重命名）压到最低。
     * </p>
     * <p>
     * <b>正常路径请求数不变</b>：这里用的 {@code getDetails(apiKey, type, id)} 与采纳后
     * {@link #fetchDetails} 是同一个调用，{@code TMDbApiService} 有 L1+DB 两层缓存，
     * 候选被采纳时第二次调用直接命中缓存；只有被否决的候选才多一次真实请求。
     * </p>
     */
    private boolean episodeCountContradicts(String type, MediaInfo info, int id, TMDbApiService api) {
        if (!"tv".equals(type) || StringUtils.isEmpty(info.getEpisode())) {
            return false;
        }
        int episode;
        try {
            episode = Integer.parseInt(info.getEpisode().replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return false;
        }
        if (episode <= 0) {
            return false;
        }
        try {
            JsonNode d = mapper.readTree(api.getDetails(apiKey, type, id));
            if (d == null) {
                return false;
            }
            int total = d.path("number_of_episodes").asInt(0);
            // 拿不到总集数（字段缺失/请求失败）时不做判断——反证只在证据确凿时生效
            return total > 0 && episode > total * EPISODE_OVERFLOW_FACTOR;
        } catch (Exception e) {
            log.debug("集数反证取详情失败，按不矛盾处理：id={}, {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * 采纳门槛：这个候选有没有一条<b>独立于 TMDb 相关度排序</b>的正面证据。
     * 标题命中 或 年份差 ≤ {@link #YEAR_CLOSE_TOLERANCE}，满足其一即可。
     * <p>
     * <b>为什么不是「分数 ≥ 某个阈值」</b>：{@link #scoreCandidate} 的分数是用来在候选之间
     * <b>排序</b>的相对量，从未校准过——它把一个 0/100 的离散项、一个 −10~+30 的档位项
     * 和一个无上界的热度项（{@code log1p(popularity)*2}）加在一起，量纲是混的。更要命的是
     * 改造前那个 +100 只在 {@code getOfficialChineseTitle} 返回非空时才触发，而该方法要求
     * TMDb 的 name/title <b>含中文</b>，于是英文剧、日番、韩剧永远拿不到这 100 分：
     * 在这样的分数上设数值门槛，实际效果是设了一道「是不是中文作品」的门槛，
     * 会把非中文内容成片拒掉。所以门槛必须落在<b>信号</b>上，不是落在分数上。
     * </p>
     */
    private boolean hasEnoughEvidence(String type, MediaInfo info, JsonNode node, int id, TMDbApiService api) {
        if (titleMatchLevel(type, info, node) > 0 || yearClose(type, info, node)) {
            return true;
        }
        return englishTitleMatches(type, info, id, api);
    }

    /**
     * 最后一道证据：拿 TMDb 的<b>英文规范名</b>（{@code language=en-US}）再比一次标题。
     * <p>
     * 搜索结果里能比的只有 {@code name}（按 {@code openlist.tmdb.metadata.language} 本地化，默认 zh-CN）
     * 与 {@code original_name}（作品母语）。对日番/韩剧/动画来说，这两个字段分别是中文译名和日文/韩文原名，
     * 而发布组用的是<b>英文/罗马字</b>标题——它一个都不在里面，{@link #titleMatchLevel} 恒为 0。
     * 年份这条证据同时又对 S2 以上的剧集失效（文件名里写的是本季播出年，候选侧是首播年，差好几年），
     * 于是「TMDb 明明搜到了正确答案」却被门槛拒掉，{@code tmdbId} 为空、
     * {@code MediaRenameProcessor} 直接跳过该文件。真实漏网案例：
     * {@code Mushoku.Tensei.Jobless.Reincarnation.S03E06.2026}（中文名/日文原名 vs 罗马字，年份差 5）、
     * {@code That.Time.I.Got.Reincarnated.as.a.Slime.S04E17.2026}（年份差 8）、
     * {@code Flex.X.Cop.S02E02}（韩剧，且文件名里压根没有年份，第二条证据在构造上就不可能成立）。
     * </p>
     * <p>
     * 与 PT 侧 {@code TmdbSearchService#resolveEnglishTitle} 同一做法（那边也踩过同一个坑）：
     * 不走 {@code /alternative_titles}——那是众包数据，长篇动画常年被登记一堆"篇章别名"，取到的
     * 根本不是剧集本身的英文名。
     * </p>
     * <p>
     * <b>只在前两条证据都不成立时才发这次请求</b>，正常命中的路径请求数不变；且
     * {@code TMDbApiService} 对 getDetails 有 L1+DB 两层缓存，同一部剧的几十集只会真正请求一次。
     * 打分（{@link #scoreCandidate}）刻意不引入英文名——那需要给<b>每个</b>候选各发一次请求，
     * 代价与收益不成比例；冠军选错时的兜底仍是「拒绝 → 降级到下一个候选标题 → AI」。
     * </p>
     */
    private boolean englishTitleMatches(String type, MediaInfo info, int id, TMDbApiService api) {
        String english = fetchEnglishCanonicalTitle(type, id, api);
        if (StringUtils.isBlank(english)) {
            return false;
        }
        boolean matched = titleMatchLevel(info, english) > 0;
        if (matched) {
            log.debug("TMDb 候选靠英文规范名「{}」通过采纳门槛：{}", english, info.getOriginalName());
        }
        return matched;
    }

    /**
     * 取 TMDb 的英文规范名。任何失败都静默返回 null——这是一次<b>可有可无</b>的补查，
     * 失败只意味着回到"证据不足、不采纳"，绝不能把异常抛给调用方而作废整次刮削
     * （同 {@link #fetchChineseAlias} 的教训：{@code TMDbApiService} HTTP 失败时返回 null，
     * 而 {@code mapper.readTree(null)} 会抛 {@code IllegalArgumentException}）。
     */
    private String fetchEnglishCanonicalTitle(String type, int id, TMDbApiService api) {
        try {
            String raw = api.getDetails(apiKey, type, id, "en-US");
            if (StringUtils.isBlank(raw)) {
                return null;
            }
            JsonNode d = mapper.readTree(raw);
            if (d == null) {
                return null;
            }
            return d.path("movie".equals(type) ? "title" : "name").asText(null);
        } catch (Exception e) {
            log.warn("获取 TMDb 英文规范名失败（type={}, id={}）：{}", type, id, e.getMessage());
            return null;
        }
    }

    /**
     * 标题匹配强度：2=归一化后全等，1=一方包含另一方，0=不匹配。
     * <p>
     * 与旧的「官方中文标题精确匹配」相比有两处关键差别：<b>语言中立</b>（不再要求候选的
     * name/title 含中文，英文/日文作品终于也能拿到标题信号），以及<b>双向多字段比较</b>
     * （解析出的三个标题 × 候选的规范名与原始名）。
     * </p>
     * <p>
     * 包含关系要求较短一方达到最小长度，防止 {@code Up}、{@code It} 这类短标题被任意长标题
     * 包含而误判。阈值按语言区分——CJK 单字的信息量远高于拉丁字母，两个汉字（「三体」）
     * 已经足够可辨，而两个拉丁字母毫无意义。
     * </p>
     */
    private int titleMatchLevel(String type, MediaInfo info, JsonNode node) {
        return titleMatchLevel(info, candidateTitleFields(type, node));
    }

    /**
     * 同上，但候选侧标题直接给字符串——供 {@link #englishTitleMatches} 拿单个英文规范名复用同一套判据。
     * 门槛与打分共用这段逻辑是硬要求，两者用不同判据会前后打架（见 {@link #scoreCandidate} 注释）。
     */
    private int titleMatchLevel(MediaInfo info, String... theirTitles) {
        List<String> mine = normalizedTitles(info.getTitle(), info.getOriginalTitle(), info.getEnglishTitle());
        List<String> theirs = normalizedTitles(theirTitles);
        for (String a : mine) {
            for (String b : theirs) {
                if (a.equals(b)) {
                    return TITLE_MATCH_EXACT;
                }
            }
        }
        for (String a : mine) {
            for (String b : theirs) {
                String shorter = a.length() <= b.length() ? a : b;
                String longer = a.length() <= b.length() ? b : a;
                int minLen = containsCjk(shorter) ? MIN_CONTAINS_LENGTH_CJK : MIN_CONTAINS_LENGTH_LATIN;
                if (shorter.length() >= minLen && longer.contains(shorter)) {
                    return TITLE_MATCH_CONTAINS;
                }
            }
        }
        return 0;
    }

    /** 解析出的年份与候选年份是否接近；任一侧缺失即判否（判不出来不算证据） */
    private boolean yearClose(String type, MediaInfo info, JsonNode node) {
        String mine = info.getYear();
        String theirs = getYearSafe(node, type);
        if (StringUtils.isEmpty(mine) || StringUtils.isEmpty(theirs)) {
            return false;
        }
        try {
            return Math.abs(Integer.parseInt(mine.trim()) - Integer.parseInt(theirs.trim()))
                    <= YEAR_CLOSE_TOLERANCE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 候选侧参与标题比较的字段：规范名 + 原始名（原始名对日番/韩剧尤其关键） */
    private String[] candidateTitleFields(String type, JsonNode node) {
        boolean movie = "movie".equals(type);
        return new String[]{
                node.path(movie ? "title" : "name").asText(null),
                node.path(movie ? "original_title" : "original_name").asText(null)
        };
    }

    private List<String> normalizedTitles(String... raw) {
        List<String> result = new ArrayList<>();
        for (String s : raw) {
            String n = normalizeForCompare(s);
            if (n != null && !result.contains(n)) {
                result.add(n);
            }
        }
        return result;
    }

    /**
     * 比较用归一化，实现收口在 {@link com.osr.openliststrm.rename.TitleNormalizer}——与 PT 订阅匹配侧
     * （{@code SubscriptionMatcher#normalize}）共用同一份字符类，不要在任一侧另写一份。
     */
    private String normalizeForCompare(String raw) {
        return com.osr.openliststrm.rename.TitleNormalizer.normalizeForCompare(raw);
    }

    private boolean containsCjk(String text) {
        return text != null && text.matches(".*[\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF].*");
    }

    /** 日志里描述一个候选，够用即可 */
    /** 候选摘要：{@code 「Re：从零开始的异世界生活」(2016)#65942}，逗号分隔前 N 名 */
    private String describeTop(String type, List<JsonNode> ranked) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranked.size() && i < MAX_CANDIDATES_EXAMINED; i++) {
            JsonNode n = ranked.get(i);
            if (i > 0) sb.append(", ");
            sb.append('「').append(describeCandidate(type, n)).append('」')
              .append('(').append(getYearSafe(n, type)).append(")#").append(n.path("id").asInt());
        }
        return sb.toString();
    }

    private String describeCandidate(String type, JsonNode node) {
        String[] fields = candidateTitleFields(type, node);
        for (String f : fields) {
            if (StringUtils.isNotEmpty(f)) {
                return f;
            }
        }
        return "id=" + node.path("id").asText("?");
    }

    private String firstNonBlankTitle(MediaInfo info) {
        for (String s : new String[]{info.getTitle(), info.getOriginalTitle(), info.getEnglishTitle()}) {
            if (StringUtils.isNotEmpty(s)) {
                return s;
            }
        }
        return "(未解析出标题)";
    }

    /**
     * 按分数从高到低排出候选顺序，替代"直接取第一条"的粗暴策略。
     * 打分维度：标题匹配强度（最高权重）、发行年份接近度、TMDb 热度（popularity）、
     * 以及 TMDb 自身相关度排序名次（作为无其他信号时的兜底，保持与旧行为一致）。
     * <p>
     * 返回整个有序列表而不是单个冠军：冠军过不了 {@link #doSearchOnce} 里那两道检验时，
     * 次席仍有机会被采纳。排序是稳定的，同分候选保持 TMDb 的原始相关度顺序。
     * </p>
     * <p>
     * <b>全等命中自成一档，排在所有非全等候选之前</b>（{@link #TITLE_MATCH_EXACT}）——
     * 年份与热度再高也跨不过这一档。单靠分数拉开是不够的：全等与包含只差 40 分，
     * 而年份挡位本身就能摆动 85 分。真实事故：{@code [梦魇绝镇 第四季].From.2026.S04E10}
     * 降级到英文名 {@code From} 后，《怪奇物语：1985故事集》（{@code Stranger Things: Tales From 85}）
     * 的原名包含 {@code from}、首播年恰好是 2026（+40）、热度又高；真正的 {@code From (2022)}
     * 标题全等拿满 100 分，却因为文件名里的 2026 是<b>本季播出年</b>、而候选侧是首播年
     * 而被扣 15 分，最终反而输掉。包含判定本就宽松（{@code from} 恰好卡在
     * {@link #MIN_CONTAINS_LENGTH_LATIN} 的下限上），不该与逐字相等争同一个名次。
     * </p>
     * <p>
     * <b>分档只抬全等，不剔除其余候选</b>：包含命中与无标题信号的候选仍按原来的分数
     * 相互排序、也仍在列表里——全等候选被集号反证否决时（{@code Perfect World} 那类事故）
     * 它们还要接着被检验，直接剔掉等于把那条修复路径一并剔掉。
     * </p>
     */
    private List<JsonNode> rankCandidates(String type, MediaInfo info, JsonNode results) {
        List<JsonNode> nodes = new ArrayList<>();
        java.util.Map<JsonNode, Double> scores = new java.util.IdentityHashMap<>();
        java.util.Map<JsonNode, Integer> tiers = new java.util.IdentityHashMap<>();
        for (int i = 0; i < results.size(); i++) {
            JsonNode node = results.get(i);
            nodes.add(node);
            // titleMatchLevel 算一次就好：分档与打分共用同一个值，不可能分叉
            int level = titleMatchLevel(type, info, node);
            tiers.put(node, level == TITLE_MATCH_EXACT ? 1 : 0);
            scores.put(node, scoreCandidate(type, info, node, level) - i * 0.5); // TMDb 原始相关度名次作为兜底权重
        }
        // List.sort 是稳定排序：同档同分时保持 TMDb 原始相关度顺序
        nodes.sort((a, b) -> {
            int byTier = Integer.compare(tiers.get(b), tiers.get(a));
            return byTier != 0 ? byTier : Double.compare(scores.get(b), scores.get(a));
        });
        return nodes;
    }

    private double scoreCandidate(String type, MediaInfo info, JsonNode node, int titleMatchLevel) {
        double score = 0;

        // 标题匹配：全等最强，一方包含另一方次之。
        // 必须与采纳门槛（hasEnoughEvidence）共用 titleMatchLevel——两者用不同判据会前后打架：
        // 候选 X 标题命中但年份偏、候选 Y 标题不命中但年份准，若打分不认标题就会选中 Y，
        // 再靠年份混过门槛，而真正的答案 X 连被检验的机会都没有。
        // 旧实现这一项是 getOfficialChineseTitle（要求候选名含中文）的精确相等，
        // 非中文作品恒为 0 分，等于只按年份+热度排序。
        // 全等与包含之间的次序不靠这 40 分保证，而靠 rankCandidates 的分档；
        // 这里的分值只决定同一档内部的相对顺序。
        score += switch (titleMatchLevel) {
            case TITLE_MATCH_EXACT -> 100;
            case TITLE_MATCH_CONTAINS -> 60;
            default -> 0;
        };

        // 发行年份接近度：越接近文件名解析出的年份分越高，差距过大则扣分（防止误选重制版/不同季）
        String targetYear = info.getYear();
        String candidateYear = getYearSafe(node, type);
        if (StringUtils.isNotEmpty(targetYear) && StringUtils.isNotEmpty(candidateYear)) {
            try {
                int diff = Math.abs(Integer.parseInt(targetYear) - Integer.parseInt(candidateYear));
                // 差得越离谱扣得越狠。旧档位里「差 21 年」和「差 4 年」同样只扣 10 分，
                // 一个字面同名的老剧靠 +100 的标题分就能轻松压过年份完全吻合的正确答案。
                // 但也不能扣到能一票否决的程度——文件名里的年份对剧集常常是本季播出年
                // 而非首播年（见 search() 注释），差十几年的正常命中（The Office S03E05.2019）
                // 必须还能靠标题分活下来，真正的否决交给 episodeCountContradicts。
                if (diff == 0) score += 40;
                else if (diff == 1) score += 20;
                else if (diff <= 3) score += 8;
                else if (diff <= 10) score -= 15;
                else score -= 45;
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
                    .map(t -> CompletableFuture.runAsync(Threads.wrap(t), executor))
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
     * JSON 响应摘要：数组报长度、对象报各数组字段的长度、其余报字段数。
     * <p>
     * {@code {backdrops=134, posters=79, logos=12}} —— 够回答「这次拉到东西了吗、大概多少」，
     * 而这正是这条日志唯一被用来回答的问题；完整响应体除了撑爆文件没有别的作用。
     * </p>
     */
    static String summarize(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isArray()) return node.size() + " 条";
        if (!node.isObject()) return node.asText();
        StringBuilder sb = new StringBuilder("{");
        node.properties().forEach(e -> {
            if (!e.getValue().isArray()) return;
            if (sb.length() > 1) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue().size());
        });
        if (sb.length() == 1) return node.size() + " 个字段";
        return sb.append('}').toString();
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
                // 打摘要而不是 node 本身：node 是完整响应体，一份 tv images 实测 26KB，
                // 12 次刮削就占掉整份日志的 15%。隔壁 fetchSeasonImagesIfNeeded 一直是对的写法。
                if (log.isDebugEnabled()) {
                    log.debug("获取{} {} 成功: {}", label, key, summarize(node));
                }
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

    /**
     * 取中文别名，按 {@link TmdbTitleRegions#CHINESE} 的地区优先级挑（大陆 → 台湾 → 香港 → 新加坡）。
     * 只认 CN 是不够的：日番、港片的中文名常常只登记在 TW/HK 下，漏掉它们就会退回英文标题。
     * 逐条校验「确实含中文」是必需的——别名是众包数据，CN 条目里登记罗马音、拼音、英文副标题的
     * 不在少数，只按地区取会把一串英文换成另一串英文。
     * <p>
     * 这是<b>锦上添花</b>的一次辅助请求，失败绝不能拖垮整次刮削——
     * {@code TMDbApiService#executeAndReturnString} 在 HTTP 失败（404 / 429 重试耗尽 / 5xx）时
     * 返回 null，而 {@code mapper.readTree(null)} 会抛 {@code IllegalArgumentException}。
     * 该异常原先会一路冒泡出 {@code doSearchOnce}、{@code search}，最终被 {@link #enrich} 的
     * {@code catch (Exception)} 吞掉：明明冠军候选已经选出来了，却因为一次可有可无的别名查询
     * 失败而整次刮削作废；更糟的是此时 {@code tmdbId} 已经写进 info，
     * {@code MediaParser#needsAI} 随之变 false，AI 兜底也不会触发，只剩一个没有标题和详情的半成品。
     * 查不到别名的正常降级是 {@code fallbackTitle}（用候选自身的 name/title），本就存在。
     */
    private String fetchChineseAlias(String type, int id, TMDbApiService api) throws IOException {
        String raw = api.getAlternativeTitles(apiKey, type, id);
        if (StringUtils.isBlank(raw)) {
            log.debug("TMDb 未返回别名数据（type={}, id={}），跳过中文别名，不影响刮削", type, id);
            return null;
        }
        JsonNode root = mapper.readTree(raw);
        if (root == null) return null;
        log.debug("TMDb 中文别名响应：{}", root);

        JsonNode titles = type.equals("movie") ? root.get("titles") : root.get("results");
        if (titles == null) return null;

        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (JsonNode t : titles) {
            String title = t.hasNonNull("title") ? t.get("title").asText()
                    : t.hasNonNull("name") ? t.get("name").asText() : null;
            if (!isChinese(title)) continue;
            int rank = TmdbTitleRegions.CHINESE.indexOf(t.path("iso_3166_1").asText());
            if (rank < 0 || rank >= bestRank) continue;
            best = title;
            bestRank = rank;
            if (rank == 0) {
                // CN 已是最高优先级，后面的条目不可能更好
                break;
            }
        }
        return best;
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
