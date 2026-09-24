package com.osr.openliststrm.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.PtLogText;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.osr.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PT 订阅的聊天指令：把一条文本指令翻译成订阅操作，生成回复文案（和 TG 上的快捷按钮）。
 * <p>
 * 与渠道无关，企业微信（{@code WeComCommandService}）与 Telegram（{@code StrmBot}）共用这一份。
 * 身份解析、消息收发留在各渠道：这里只认 {@link ChatUser}，拿到的已经是解析好的 OSR 用户。
 * <p>
 * <b>建订阅是多轮的</b>：搜索 → 回序号选片 → （剧集）回序号选季 → 落库。中间状态放在
 * {@link ChatSessionStore}，因为两个渠道的文本消息都不带上下文。
 * <p>
 * 本类只返回文案、不负责发送，因此可以脱离任何聊天服务端直接单测。
 *
 * @author Jack
 */
@Slf4j
@Service
public class PtChatCommandService {

    /** 搜索候选最多展示几条。手机上一屏有限，给太多反而要滚动着找 */
    private static final int MAX_CANDIDATES = 5;

    /** 「我的订阅」最多列几条，超出提示去网页端看 */
    private static final int MAX_LIST_SIZE = 15;

    /** 「最近入库」最多列几条 */
    private static final int MAX_RECENT_SIZE = 10;

    /** 选季按钮一行放几个 */
    private static final int SEASON_BUTTONS_PER_ROW = 5;

    /**
     * 选择类按钮的指令形如 {@code #k3x9f:2}：会话 id + 序号。
     * <p>
     * 不直接用「2」：TG 上旧消息的按钮一直点得到，用户翻回上一轮搜索结果点一下，
     * 裸序号会被当成<b>当前</b>这轮的第 2 项，订上一部他根本没选的剧。带上会话 id 就能认出来。
     */
    private static final Pattern PICK_COMMAND = Pattern.compile("^#([0-9a-z]+):(\\d+)$");

    static final String HELP_TEXT = """
            OSR 订阅助手，可用指令：

            订阅 <剧名>       搜索剧集并订阅，如：订阅 三体
            订阅电影 <片名>   搜索电影并订阅
            我的订阅          查看自己的订阅列表
            下载中            查看正在下载的集
            最近入库          查看最近入库的集
            进度 <编号>       查看某条订阅的进度
            补搜 <编号>       立即搜索该订阅的全部缺集
            暂停 <编号>       暂停订阅
            恢复 <编号>       恢复订阅
            我的账号          查看绑定状态
            取消              中断当前的多轮选择
            帮助              显示本说明

            搜索后直接回复序号即可选择。""";

    @Autowired
    private TmdbSearchService tmdbSearchService;
    @Autowired
    private SubscriptionService subscriptionBiz;
    @Autowired
    private IPtSubscriptionPlusService subscriptionService;
    @Autowired
    private IPtSubscriptionEpisodePlusService episodeService;
    @Autowired
    private IPtDownloadRecordPlusService downloadRecordService;
    @Autowired
    private SubscriptionSearchOnCreateTrigger searchOnCreateTrigger;
    @Autowired
    private SearchSupplementService searchSupplementService;
    @Autowired
    private ChatSessionStore sessionStore;

    /**
     * 正在补搜的订阅。补搜要跑几分钟，TG 上按钮一连点几下、或企微里连发两遍，
     * 不拦的话同一条订阅会并发搜几轮、把同一个资源推好几次。
     */
    private final Set<Integer> searching = ConcurrentHashMap.newKeySet();

    /**
     * 处理一条指令。异常在这里兜住并转成提示文案，调用方不必再包。
     *
     * @param text 指令文本（渠道已去掉 TG 的斜杠命令、企微的菜单 key 等外壳）
     */
    public ChatReply handle(ChatUser user, String text) {
        String command = text == null ? "" : text.trim();
        try {
            return dispatch(user, command);
        } catch (Exception e) {
            log.warn("处理聊天指令失败，session={} command={}：{}", user.sessionKey(), command, e.getMessage(), e);
            return ChatReply.of("处理失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private ChatReply dispatch(ChatUser user, String text) {
        String sessionKey = user.sessionKey();

        if (matches(text, "帮助", "help", "?", "？")) {
            sessionStore.clear(sessionKey);
            return ChatReply.of(HELP_TEXT);
        }
        if (matches(text, "取消", "cancel")) {
            sessionStore.clear(sessionKey);
            return ChatReply.of("已取消当前操作。");
        }
        if (matches(text, "我的订阅", "订阅列表", "list")) {
            sessionStore.clear(sessionKey);
            return listSubscriptions(user);
        }
        if (matches(text, "下载中", "在下载", "downloading")) {
            sessionStore.clear(sessionKey);
            return ChatReply.of(listDownloading(user));
        }
        if (matches(text, "最近入库", "已入库", "recent")) {
            sessionStore.clear(sessionKey);
            return ChatReply.of(listRecentInLibrary(user));
        }
        if (matches(text, "我的账号", "我是谁", "whoami")) {
            sessionStore.clear(sessionKey);
            return ChatReply.of(describeAccount(user));
        }

        String movieKeyword = stripPrefix(text, "订阅电影", "订阅 电影", "电影订阅");
        if (movieKeyword != null) {
            return startSearch(sessionKey, TmdbSearchService.TYPE_MOVIE, movieKeyword);
        }
        String tvKeyword = stripPrefix(text, "订阅剧集", "订阅 剧集", "订阅剧", "订阅 剧", "订阅");
        if (tvKeyword != null) {
            return startSearch(sessionKey, TmdbSearchService.TYPE_TV, tvKeyword);
        }

        String progressArg = stripPrefix(text, "进度", "查看");
        if (progressArg != null) {
            return showProgress(user, progressArg);
        }
        String searchArg = stripPrefix(text, "补搜", "搜索缺集");
        if (searchArg != null) {
            return ChatReply.of(searchMissing(user, searchArg));
        }
        String pauseArg = stripPrefix(text, "暂停");
        if (pauseArg != null) {
            return switchStatus(user, pauseArg, true);
        }
        String resumeArg = stripPrefix(text, "恢复", "启用");
        if (resumeArg != null) {
            return switchStatus(user, resumeArg, false);
        }

        Matcher pick = PICK_COMMAND.matcher(text);
        if (pick.matches()) {
            ChatSessionStore.ChatSession session = sessionStore.get(sessionKey);
            if (session == null || !session.id().equals(pick.group(1))) {
                return ChatReply.of("这组选项已过期，请重新搜索，例如：订阅 三体");
            }
            return continueSession(user, session, Integer.parseInt(pick.group(2)));
        }

        // 纯数字：只有在多轮会话进行中才有意义，否则用户多半是打错了
        Integer number = parseNumber(text);
        if (number != null) {
            ChatSessionStore.ChatSession session = sessionStore.get(sessionKey);
            if (session == null) {
                return ChatReply.of("当前没有待选择的内容（超过 10 分钟会自动失效）。\n请先发起搜索，例如：订阅 三体");
            }
            return continueSession(user, session, number);
        }
        return ChatReply.of("看不懂这条指令。\n\n" + HELP_TEXT);
    }

    // ---------------- 建订阅：搜索 → 选片 → 选季 ----------------

    private ChatReply startSearch(String sessionKey, String mediaType, String keyword) {
        boolean movie = TmdbSearchService.TYPE_MOVIE.equalsIgnoreCase(mediaType);
        if (StringUtils.isBlank(keyword)) {
            // 引导语必须给出与当前媒体类型一致的指令：点了「订阅电影」却提示「订阅 三体」，
            // 照着发只会去搜剧集
            return ChatReply.of(movie
                    ? "请带上片名，例如：订阅电影 流浪地球"
                    : "请带上剧名，例如：订阅 三体");
        }
        List<TmdbSearchItem> results;
        try {
            results = tmdbSearchService.search(mediaType, keyword);
        } catch (IllegalArgumentException e) {
            return ChatReply.of("搜索失败：" + e.getMessage());
        }
        if (results == null || results.isEmpty()) {
            sessionStore.clear(sessionKey);
            return ChatReply.of("没搜到「" + keyword + "」，换个关键词试试。");
        }
        List<TmdbSearchItem> candidates = results.size() > MAX_CANDIDATES
                ? results.subList(0, MAX_CANDIDATES) : results;
        ChatSessionStore.ChatSession session = sessionStore.awaitMediaSelect(sessionKey, List.copyOf(candidates));

        StringBuilder sb = new StringBuilder("搜到以下结果，回复序号选择：\n");
        List<List<ChatReply.Button>> buttons = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String line = (i + 1) + ". " + describe(candidates.get(i));
            sb.append('\n').append(line);
            buttons.add(List.of(new ChatReply.Button(line, pickCommand(session, i + 1))));
        }
        return ChatReply.of(sb.toString(), buttons);
    }

    /** 收到一个序号：按当前会话阶段决定它是「选第几部作品」还是「选第几季」 */
    private ChatReply continueSession(ChatUser user, ChatSessionStore.ChatSession session, int number) {
        if (session.stage() == ChatSessionStore.Stage.AWAIT_MEDIA) {
            return selectMedia(user, session, number);
        }
        return selectSeason(user, session, number);
    }

    private ChatReply selectMedia(ChatUser user, ChatSessionStore.ChatSession session, int index) {
        List<TmdbSearchItem> candidates = session.candidates();
        if (index < 1 || index > candidates.size()) {
            return ChatReply.of("序号超出范围，请回复 1 ~ " + candidates.size() + " 之间的数字。");
        }
        TmdbSearchItem selected = candidates.get(index - 1);
        String sessionKey = user.sessionKey();

        if (TmdbSearchService.TYPE_MOVIE.equalsIgnoreCase(selected.getMediaType())) {
            sessionStore.clear(sessionKey);
            return doSubscribe(user, selected, null);
        }

        int latestSeason;
        try {
            latestSeason = tmdbSearchService.getLatestSeasonNumber(selected.getTmdbId());
        } catch (Exception e) {
            log.warn("查询剧集[{}]季数失败：{}", selected.getTmdbId(), e.getMessage());
            latestSeason = 1;
        }
        if (latestSeason <= 1) {
            // 只有一季就别多问一轮了
            sessionStore.clear(sessionKey);
            return doSubscribe(user, selected, 1);
        }
        ChatSessionStore.ChatSession next = sessionStore.awaitSeasonSelect(sessionKey, selected, latestSeason);
        List<List<ChatReply.Button>> buttons = new ArrayList<>();
        List<ChatReply.Button> row = new ArrayList<>();
        for (int season = 1; season <= latestSeason; season++) {
            row.add(new ChatReply.Button("第" + season + "季", pickCommand(next, season)));
            if (row.size() == SEASON_BUTTONS_PER_ROW) {
                buttons.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            buttons.add(row);
        }
        return ChatReply.of("「" + selected.getTitle() + "」共 " + latestSeason + " 季，回复季号订阅（1 ~ "
                + latestSeason + "）。", buttons);
    }

    private ChatReply selectSeason(ChatUser user, ChatSessionStore.ChatSession session, int season) {
        if (season < 1 || season > session.latestSeason()) {
            return ChatReply.of("季号超出范围，请回复 1 ~ " + session.latestSeason() + " 之间的数字。");
        }
        sessionStore.clear(user.sessionKey());
        return doSubscribe(user, session.selected(), season);
    }

    /** 真正落库。season 传 null 表示电影 */
    private ChatReply doSubscribe(ChatUser user, TmdbSearchItem item, Integer season) {
        SubscribeRequest request = new SubscribeRequest();
        request.setTmdbId(item.getTmdbId());
        request.setMediaType(item.getMediaType());
        request.setSeason(season);
        request.setOwnerUserId(user.sysUserId());

        PtSubscriptionPlus sub;
        try {
            sub = subscriptionBiz.subscribe(request);
        } catch (IllegalArgumentException e) {
            return ChatReply.of("订阅失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("聊天指令建订阅失败，tmdbId={}：{}", item.getTmdbId(), e.getMessage(), e);
            return ChatReply.of("订阅失败，请稍后重试或到网页端操作。");
        }
        if (SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            try {
                searchOnCreateTrigger.triggerAsync(sub.getId());
            } catch (Exception e) {
                // 补搜只是加速首次抓取，失败不影响订阅本身，正常的 RSS 轮询照样会命中
                log.warn("{} 建订阅后补搜触发失败：{}", PtLogText.subject(sub), e.getMessage());
            }
        }
        String text = "已订阅：" + describe(sub) + "\n编号 " + sub.getId()
                + "，共 " + sub.getTotalEpisodes() + " 集"
                + (SubscriptionService.STATUS_COMPLETED.equals(sub.getStatus())
                ? "\n媒体库里已全部入库，无需下载。"
                : "\n已开始搜索资源，有进展会通知你。");
        return ChatReply.of(text, List.of(List.of(new ChatReply.Button("查看进度", "进度 " + sub.getId()))));
    }

    // ---------------- 查询与状态操作 ----------------

    /**
     * 当前用户可见的订阅，倒序。可见性规则与网页端一致：
     * 管理员看全部，其余人看「自己的 + 无归属的历史公共订阅」。
     *
     * @param limit 最多取几条
     */
    private List<PtSubscriptionPlus> listVisibleSubscriptions(ChatUser user, int limit) {
        LambdaQueryWrapper<PtSubscriptionPlus> wrapper = new LambdaQueryWrapper<>();
        if (!SysUser.isAdmin(user.sysUserId())) {
            Long ownerId = user.sysUserId();
            wrapper.and(w -> w.eq(PtSubscriptionPlus::getOwnerUserId, ownerId)
                    .or().isNull(PtSubscriptionPlus::getOwnerUserId));
        }
        wrapper.orderByDesc(PtSubscriptionPlus::getId);
        if (limit != Integer.MAX_VALUE) {
            wrapper.last("limit " + limit);
        }
        return subscriptionService.list(wrapper);
    }

    private ChatReply listSubscriptions(ChatUser user) {
        // 多查一条用来判断「还有更多」，不必再发一次 count 查询
        List<PtSubscriptionPlus> subs = listVisibleSubscriptions(user, MAX_LIST_SIZE + 1);
        if (subs.isEmpty()) {
            return ChatReply.of("你还没有订阅。发送「订阅 剧名」开始第一条。");
        }
        boolean truncated = subs.size() > MAX_LIST_SIZE;
        StringBuilder sb = new StringBuilder("你的订阅：\n");
        List<List<ChatReply.Button>> buttons = new ArrayList<>();
        for (PtSubscriptionPlus sub : truncated ? subs.subList(0, MAX_LIST_SIZE) : subs) {
            sb.append('\n').append(sub.getId()).append(". ").append(describe(sub))
                    .append('（').append(statusText(sub.getStatus())).append('）');
            buttons.add(List.of(new ChatReply.Button(sub.getId() + ". " + describe(sub), "进度 " + sub.getId())));
        }
        if (truncated) {
            sb.append("\n\n仅显示最近 ").append(MAX_LIST_SIZE).append(" 条，完整列表请到网页端查看。");
        }
        sb.append("\n\n发送「进度 编号」查看详情。");
        return ChatReply.of(sb.toString(), buttons);
    }

    /**
     * 正在下载的集，按订阅分组。
     * <p>
     * 先取用户可见的订阅、再按 subId 批量查集，而不是反过来从全部在途集里筛——
     * 后者会把别人订阅的下载动态也捞出来。
     */
    private String listDownloading(ChatUser user) {
        List<PtSubscriptionPlus> subs = listVisibleSubscriptions(user, MAX_LIST_SIZE + 1);
        if (subs.isEmpty()) {
            return "你还没有订阅。发送「订阅 剧名」开始第一条。";
        }
        Map<Integer, PtSubscriptionPlus> subById = subs.stream()
                .collect(Collectors.toMap(PtSubscriptionPlus::getId, s -> s, (a, b) -> a, LinkedHashMap::new));
        List<PtSubscriptionEpisodePlus> inFlight = episodeService.list(
                new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                        .in(PtSubscriptionEpisodePlus::getSubId, subById.keySet())
                        .eq(PtSubscriptionEpisodePlus::getState, SubscriptionService.STATE_IN_FLIGHT)
                        .orderByAsc(PtSubscriptionEpisodePlus::getSubId)
                        .orderByAsc(PtSubscriptionEpisodePlus::getEpisode));
        if (inFlight.isEmpty()) {
            return "当前没有正在下载的集。";
        }
        // 一次把用到的下载记录查出来，避免逐集查库
        Map<Integer, PtDownloadRecordPlus> recordById = loadRecords(inFlight);

        StringBuilder sb = new StringBuilder("正在下载：\n");
        Integer lastSubId = null;
        for (PtSubscriptionEpisodePlus episode : inFlight) {
            if (!episode.getSubId().equals(lastSubId)) {
                sb.append('\n').append(describe(subById.get(episode.getSubId()))).append('\n');
                lastSubId = episode.getSubId();
            }
            sb.append("  ").append(episodeLabel(subById.get(episode.getSubId()), episode.getEpisode()));
            PtDownloadRecordPlus record = recordById.get(episode.getDownloadId());
            if (record != null && record.getProgress() != null) {
                sb.append("  ").append(Math.round(record.getProgress() * 100)).append('%');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 最近入库的集。按集行的更新时间倒序——入库是这张表最后一次状态变更，
     * 没有单独的入库时间字段，update_time 就是最接近的信号。
     */
    private String listRecentInLibrary(ChatUser user) {
        List<PtSubscriptionPlus> subs = listVisibleSubscriptions(user, MAX_LIST_SIZE + 1);
        if (subs.isEmpty()) {
            return "你还没有订阅。发送「订阅 剧名」开始第一条。";
        }
        Map<Integer, PtSubscriptionPlus> subById = subs.stream()
                .collect(Collectors.toMap(PtSubscriptionPlus::getId, s -> s, (a, b) -> a));
        List<PtSubscriptionEpisodePlus> episodes = episodeService.list(
                new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                        .in(PtSubscriptionEpisodePlus::getSubId, subById.keySet())
                        .eq(PtSubscriptionEpisodePlus::getState, SubscriptionService.STATE_IN_LIBRARY)
                        .orderByDesc(PtSubscriptionEpisodePlus::getUpdateTime)
                        .last("limit " + MAX_RECENT_SIZE));
        if (episodes.isEmpty()) {
            return "还没有已入库的集。";
        }
        StringBuilder sb = new StringBuilder("最近入库：\n");
        for (PtSubscriptionEpisodePlus episode : episodes) {
            PtSubscriptionPlus sub = subById.get(episode.getSubId());
            sb.append('\n').append(describe(sub)).append(' ').append(episodeLabel(sub, episode.getEpisode()));
        }
        return sb.toString();
    }

    /** 绑定状态。排查「为什么收不到通知/指令没反应」时第一个要看的就是这个 */
    private String describeAccount(ChatUser user) {
        long subCount = listVisibleSubscriptions(user, Integer.MAX_VALUE).size();
        return user.accountText()
                + "\n可见订阅：" + subCount + " 条"
                + (SysUser.isAdmin(user.sysUserId()) ? "\n（管理员，可见全部订阅）" : "");
    }

    /** 批量取集关联的下载记录，避免在循环里逐条查库 */
    private Map<Integer, PtDownloadRecordPlus> loadRecords(List<PtSubscriptionEpisodePlus> episodes) {
        Set<Integer> ids = episodes.stream()
                .map(PtSubscriptionEpisodePlus::getDownloadId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return downloadRecordService.listByIds(ids).stream()
                .collect(Collectors.toMap(PtDownloadRecordPlus::getId, r -> r, (a, b) -> a));
    }

    /** 电影不带集号 */
    private static String episodeLabel(PtSubscriptionPlus sub, Integer episode) {
        if (sub == null || SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return "正片";
        }
        return "第 " + episode + " 集";
    }

    private ChatReply showProgress(ChatUser user, String arg) {
        Integer subId = parseNumber(arg);
        if (subId == null) {
            return ChatReply.of("请带上订阅编号，例如：进度 3（编号见「我的订阅」）");
        }
        PtSubscriptionPlus sub = requireAccessible(user, subId);
        if (sub == null) {
            return ChatReply.of("订阅不存在或无权访问。");
        }
        SubscriptionProgress progress;
        try {
            progress = subscriptionBiz.getProgress(subId);
        } catch (IllegalArgumentException e) {
            return ChatReply.of("查询失败：" + e.getMessage());
        }
        StringBuilder sb = new StringBuilder(describe(sub))
                .append('\n').append("状态：").append(statusText(sub.getStatus()))
                .append('\n').append("已入库：").append(progress.getInLibraryCount()).append('/').append(progress.getTotalEpisodes())
                .append('\n').append("下载中：").append(progress.getInFlightCount()).append(" 集");
        List<Integer> missing = progress.getMissingEpisodes();
        boolean hasMissing = missing != null && !missing.isEmpty();
        if (hasMissing) {
            sb.append('\n').append("仍缺：").append(formatMissing(missing));
        }

        List<ChatReply.Button> row = new ArrayList<>();
        if (SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            if (hasMissing) {
                row.add(new ChatReply.Button("立即补搜", "补搜 " + subId));
            }
            row.add(new ChatReply.Button("暂停", "暂停 " + subId));
        } else if (SubscriptionService.STATUS_PAUSED.equals(sub.getStatus())) {
            row.add(new ChatReply.Button("恢复", "恢复 " + subId));
        }
        row.add(new ChatReply.Button("刷新", "进度 " + subId));
        return ChatReply.of(sb.toString(), List.of(row));
    }

    /**
     * 立即补搜一条订阅的全部缺集，与缺集体检页的「立即补搜」是同一件事（{@code searchAndPushMissing}）。
     * <p>
     * 网页端那个接口是同步的，但它可能跑好几分钟（季搜索 + 单集补发各有预算兜着）：聊天里
     * 让用户对着一条没回音的消息干等几分钟，他只会以为没点上、再点一次。所以这里先回一句
     * 「已开始」，搜完再经 {@link ChatUser#laterReply()} 补发结果。
     */
    private String searchMissing(ChatUser user, String arg) {
        Integer subId = parseNumber(arg);
        if (subId == null) {
            return "请带上订阅编号，例如：补搜 3（编号见「我的订阅」）";
        }
        PtSubscriptionPlus sub = requireAccessible(user, subId);
        if (sub == null) {
            return "订阅不存在或无权访问。";
        }
        if (!SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            return "「" + describe(sub) + "」当前" + statusText(sub.getStatus()) + "，只有订阅中的才能补搜。";
        }
        if (searchSupplementService.hasNoEnabledIndexer()) {
            return "没有启用中的索引器，无法搜索。请到网页端「索引器」页面添加或启用至少一个。";
        }
        if (!searching.add(subId)) {
            return "「" + describe(sub) + "」正在补搜中，完成后会通知你。";
        }
        String subject = describe(sub);
        try {
            // Threads.wrap：补搜里的每一行日志都要能与触发它的这条指令对上号
            Thread.ofVirtual().name("chat-search-" + subId).start(Threads.wrap(() -> runSearch(user, subId, subject)));
        } catch (RuntimeException e) {
            searching.remove(subId);
            throw e;
        }
        return "已开始补搜「" + subject + "」的缺集，可能要几分钟，完成后通知你。";
    }

    private void runSearch(ChatUser user, Integer subId, String subject) {
        String result;
        try {
            result = summarize(searchSupplementService.searchAndPushMissing(subId));
        } catch (Exception e) {
            log.warn("聊天指令补搜失败，subId={}：{}", subId, e.getMessage(), e);
            result = "补搜失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            searching.remove(subId);
        }
        try {
            user.laterReply().accept("「" + subject + "」补搜完成：\n" + result);
        } catch (Exception e) {
            log.warn("补搜结果回发失败，session={} subId={}：{}", user.sessionKey(), subId, e.getMessage(), e);
        }
    }

    /** 文案口径与缺集体检页「立即补搜」一致：落空时把真实原因原样给出，不换成泛化提示 */
    static String summarize(SearchAndPushSummary summary) {
        if (summary.isSkipped()) {
            return "当前没有可搜索的缺集（未播出的集不参与搜索）。";
        }
        if (summary.anyPushed()) {
            int pushed = summary.getEpisodesPushed() + (summary.isSeasonPushed() ? 1 : 0);
            return "已推送 " + pushed + " 个资源到下载器。";
        }
        return StringUtils.isNotBlank(summary.getRejectSummary())
                ? "未推送任何资源：" + summary.getRejectSummary()
                : "未搜到任何候选资源，可检查订阅标题/季号与索引器配置。";
    }

    private ChatReply switchStatus(ChatUser user, String arg, boolean pause) {
        Integer subId = parseNumber(arg);
        if (subId == null) {
            return ChatReply.of("请带上订阅编号，例如：" + (pause ? "暂停 3" : "恢复 3"));
        }
        PtSubscriptionPlus sub = requireAccessible(user, subId);
        if (sub == null) {
            return ChatReply.of("订阅不存在或无权访问。");
        }
        try {
            if (pause) {
                subscriptionBiz.pause(subId);
            } else {
                subscriptionBiz.resume(subId);
            }
        } catch (IllegalArgumentException e) {
            return ChatReply.of("操作失败：" + e.getMessage());
        }
        return ChatReply.of((pause ? "已暂停：" : "已恢复：") + describe(sub),
                List.of(List.of(new ChatReply.Button("查看进度", "进度 " + subId))));
    }

    /**
     * 取订阅并校验当前用户有权访问，规则与网页端一致（管理员全量；其余人只能碰自己的和无归属的）。
     *
     * @return 无权访问或不存在时返回 null——两种情况回同一句提示，避免变成一个探测别人订阅的接口
     */
    private PtSubscriptionPlus requireAccessible(ChatUser user, Integer subId) {
        PtSubscriptionPlus sub = subscriptionService.getById(subId);
        if (sub == null) {
            return null;
        }
        if (SysUser.isAdmin(user.sysUserId())
                || sub.getOwnerUserId() == null
                || sub.getOwnerUserId().equals(user.sysUserId())) {
            return sub;
        }
        return null;
    }

    // ---------------- 文本工具 ----------------

    private static String pickCommand(ChatSessionStore.ChatSession session, int number) {
        return "#" + session.id() + ":" + number;
    }

    /**
     * 命中任一前缀则返回其后的参数（可能是空串），都不命中返回 null。
     * <p>
     * 返回空串和返回 null 是两种不同结果：「订阅」（空串）该提示怎么用，
     * 「我的订阅」（null）则应该继续往下匹配别的指令。
     */
    private static String stripPrefix(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.equalsIgnoreCase(prefix)) {
                return "";
            }
            if (text.length() > prefix.length() && text.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return text.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static boolean matches(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 解析纯数字，非纯数字返回 null（不用 NumberUtils.toInt 的 0 兜底：0 是合法季号/集号，会误判） */
    private static Integer parseNumber(String text) {
        if (StringUtils.isBlank(text) || !StringUtils.isNumeric(text.trim())) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(TmdbSearchItem item) {
        return StringUtils.isNotBlank(item.getYear())
                ? item.getTitle() + " (" + item.getYear() + ")"
                : item.getTitle();
    }

    private static String describe(PtSubscriptionPlus sub) {
        StringBuilder sb = new StringBuilder(sub.getTitle());
        if (StringUtils.isNotBlank(sub.getYear())) {
            sb.append(" (").append(sub.getYear()).append(')');
        }
        if (!SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()) && sub.getSeason() != null) {
            sb.append(" 第").append(sub.getSeason()).append("季");
        }
        return sb.toString();
    }

    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case SubscriptionService.STATUS_ACTIVE -> "订阅中";
            case SubscriptionService.STATUS_PAUSED -> "已暂停";
            case SubscriptionService.STATUS_COMPLETED -> "已完成";
            default -> status;
        };
    }

    /** 缺集号列表，超过 10 个只显示前 10 个加省略，避免一条消息几百个数字 */
    private static String formatMissing(List<Integer> missing) {
        List<Integer> shown = missing.size() > 10 ? missing.subList(0, 10) : missing;
        String joined = StringUtils.join(shown, ", ");
        return missing.size() > 10 ? joined + " 等 " + missing.size() + " 集" : joined;
    }
}
