package com.osr.openliststrm.pt.subscription;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.FaultThrottle;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.openai.AiChatService;
import com.osr.openliststrm.pt.model.TorrentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 本地正则解析不出来的种子标题，交给 AI 兜底（「参数设置 → OpenAI 配置」里的开关，默认关）。
 * <p>
 * <b>绝不在匹配路径上同步调 AI。</b>RSS 一轮几十上百条、补搜一次几十条，逐条等几秒的 AI
 * 会把一轮拖成几分钟。做法是「查缓存，未命中就排队」：本轮这条种子照旧按本地结果处理（多半匹配不上），
 * 后台每分钟取一批去问 AI，结果进缓存；同一个标题下一轮 RSS 或下一次补搜再出现时就用上了。
 * 种子在 RSS 里会挂好几轮、补搜会反复搜到同一批，所以这个延迟换来的是零阻塞。
 * </p>
 * <p>
 * 成本护栏：每轮最多 {@value #BATCHES_PER_ROUND} 次调用、每次 {@value #BATCH_SIZE} 条；排队上限 {@value #QUEUE_LIMIT}；
 * 缓存 {@value #CACHE_SIZE} 条 LRU（只在内存里，重启后重新问一遍，量级可以接受）。
 * AI 答「认不出」的也记进缓存（{@link #NONE}），否则同一条怪标题会被反复送去问。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class TitleAiFallback {

    public static final String CONFIG_KEY = "openlist.openai.pt-title-fallback";

    static final int CACHE_SIZE = 5000;
    static final int QUEUE_LIMIT = 200;
    static final int BATCH_SIZE = 10;
    static final int BATCHES_PER_ROUND = 2;

    /** AI 的解析结果，字段缺失为 null */
    public record Parsed(String title, String year, Integer season, Integer episode, Integer episodeEnd) {
    }

    /** 问过了、AI 也认不出：缓存它，免得反复送同一条去问 */
    static final Parsed NONE = new Parsed(null, null, null, null, null);

    private final AiChatService ai;
    private final OpenlistConfig openlistConfig;

    private final Map<String, Parsed> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Parsed> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();
    private final FaultThrottle faults = new FaultThrottle();

    public TitleAiFallback(AiChatService ai, OpenlistConfig openlistConfig) {
        this.ai = ai;
        this.openlistConfig = openlistConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");
        scheduler.scheduleWithFixedDelay(Threads.wrap(this::drainOnce), Instant.now().plusSeconds(60), Duration.ofSeconds(60));
        log.info("TitleAiFallback started, interval=60s（开关 {}，默认关）", CONFIG_KEY);
    }

    boolean enabled() {
        return "1".equals(openlistConfig.getPtTitleAiFallback()) && ai.available();
    }

    /**
     * 本地解析得不到可用结构的才需要兜底：没有标题，或季、集、年份一样都没有
     * （这种种子既当不成剧集也当不成电影，本地结果对匹配毫无用处）。
     */
    static boolean needsAi(TorrentInfo t) {
        return StringUtils.isBlank(t.getParsedTitle())
                || (t.getParsedSeason() == null && t.getParsedEpisode() == null && StringUtils.isBlank(t.getParsedYear()));
    }

    /**
     * 查缓存。命中且 AI 认得出时返回结果；未命中时排队并返回 null（本轮照旧用本地结果）。
     */
    public Parsed lookup(String rawTitle) {
        if (StringUtils.isBlank(rawTitle) || !enabled()) {
            return null;
        }
        synchronized (this) {
            Parsed cached = cache.get(rawTitle);
            if (cached != null) {
                return cached == NONE ? null : cached;
            }
            if (pending.size() < QUEUE_LIMIT) {
                pending.add(rawTitle);
            }
        }
        return null;
    }

    /** 只补本地没解析出来的字段，绝不覆盖——本地正则认出来的东西比 AI 的猜测可靠 */
    public static void apply(TorrentInfo t, Parsed p) {
        if (StringUtils.isBlank(t.getParsedTitle()) && StringUtils.isNotBlank(p.title())) {
            t.setParsedTitle(p.title());
        }
        if (StringUtils.isBlank(t.getParsedYear()) && p.year() != null) {
            t.setParsedYear(p.year());
        }
        if (t.getParsedSeason() == null && p.season() != null) {
            t.setParsedSeason(p.season());
        }
        if (t.getParsedEpisode() == null && p.episode() != null) {
            t.setParsedEpisode(p.episode());
            if (t.getParsedEpisodeEnd() == null && p.episodeEnd() != null && p.episodeEnd() > p.episode()) {
                t.setParsedEpisodeEnd(p.episodeEnd());
            }
        }
    }

    void drainOnce() {
        if (!enabled()) {
            return;
        }
        int resolved = 0;
        for (int round = 0; round < BATCHES_PER_ROUND; round++) {
            List<String> batch = takeBatch();
            if (batch.isEmpty()) {
                break;
            }
            Object reply = ai.chatJson("种子标题兜底解析", buildPrompt(batch), 150 * batch.size());
            if (!(reply instanceof JSONArray array)) {
                // 调用失败或答非所问：不缓存（下次看到还会重新排队），故障只报开始与恢复
                FaultThrottle.Decision d = faults.onFailure("ai");
                if (d.shouldReport()) {
                    log.warn("AI 兜底解析种子标题失败（连续 {} 次），本批 {} 条留待下次重试", d.consecutiveFailures(), batch.size());
                }
                return;
            }
            if (faults.onSuccess("ai")) {
                log.info("AI 兜底解析种子标题已恢复");
            }
            Map<String, Parsed> parsed = parseReply(array, batch);
            synchronized (this) {
                for (String title : batch) {
                    Parsed p = parsed.getOrDefault(title, NONE);
                    cache.put(title, p);
                    if (p != NONE) {
                        resolved++;
                    }
                }
            }
        }
        if (resolved > 0) {
            log.info("AI 兜底解析了 {} 条本地认不出的种子标题，下次搜到时生效", resolved);
        }
    }

    private synchronized List<String> takeBatch() {
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        Iterator<String> it = pending.iterator();
        while (it.hasNext() && batch.size() < BATCH_SIZE) {
            batch.add(it.next());
            it.remove();
        }
        return batch;
    }

    static String buildPrompt(List<String> titles) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 PT 种子标题解析器。下面每行是一个种子标题，前面的数字是编号。\n");
        sb.append("对每个标题给出：作品名 title（中文作品优先给中文名，否则给原名；不要带季集、分辨率、发布组等信息）、");
        sb.append("年份 year（4 位数字）、季号 season、集号 episode、区间包的结束集号 episodeEnd。\n");
        sb.append("只回复一个 JSON 数组，不要任何解释，每个元素形如 ");
        sb.append("{\"i\":编号,\"title\":\"...\",\"year\":\"2024\",\"season\":1,\"episode\":5,\"episodeEnd\":null}。");
        sb.append("不知道的字段写 null；整季包 episode 写 null；电影 season 与 episode 都写 null；完全认不出作品名的 title 写 null。\n\n");
        for (int i = 0; i < titles.size(); i++) {
            sb.append(i).append(". ").append(titles.get(i).replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    /** 按编号对回原标题，逐字段校验；不合规的字段丢掉，title 为空的整条当作认不出 */
    static Map<String, Parsed> parseReply(JSONArray array, List<String> titles) {
        Map<String, Parsed> result = new HashMap<>();
        for (int k = 0; k < array.size(); k++) {
            JSONObject o = array.get(k) instanceof JSONObject obj ? obj : null;
            Integer i = o == null ? null : safeInt(o.get("i"));
            if (i == null || i < 0 || i >= titles.size()) {
                continue;
            }
            String title = StringUtils.trimToNull(o.getString("title"));
            if (title == null || "null".equalsIgnoreCase(title)) {
                continue;
            }
            String year = StringUtils.trimToNull(o.getString("year"));
            if (year != null && !(year.matches("\\d{4}") && year.compareTo("1900") >= 0 && year.compareTo("2100") <= 0)) {
                year = null;
            }
            result.put(titles.get(i), new Parsed(title, year,
                    bounded(safeInt(o.get("season")), 0, 100),
                    bounded(safeInt(o.get("episode")), 0, 5000),
                    bounded(safeInt(o.get("episodeEnd")), 0, 5000)));
        }
        return result;
    }

    private static Integer safeInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && s.trim().matches("\\d{1,5}")) {
            return Integer.valueOf(s.trim());
        }
        return null;
    }

    private static Integer bounded(Integer v, int min, int max) {
        return v == null || v < min || v > max ? null : v;
    }
}
