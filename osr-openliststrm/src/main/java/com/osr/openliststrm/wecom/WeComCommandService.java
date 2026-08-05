package com.osr.openliststrm.wecom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.osr.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 企业微信指令处理：把成员发来的文本翻译成订阅操作，并生成回复文案。
 * <p>
 * <b>身份</b>：每条指令都先经 {@code wecom_user} 把企微 UserId 换成 OSR 用户，未绑定直接拒绝。
 * 建出来的订阅归属该 OSR 用户，查询/操作也只能碰到自己的订阅——这是「分不同用户」的落点。
 * <p>
 * <b>建订阅是多轮的</b>：搜索 → 回序号选片 → （剧集）回序号选季 → 落库。中间状态放在
 * {@link WeComSessionStore}，因为企微文本消息不带任何上下文。
 * <p>
 * 本类只返回文案、不负责发送，因此可以脱离企微服务端直接单测。
 *
 * @author Jack
 */
@Slf4j
@Service
public class WeComCommandService {

    /** 搜索候选最多展示几条。企微文本消息在手机上一屏有限，给太多反而要滚动着找 */
    private static final int MAX_CANDIDATES = 5;

    /** 「我的订阅」最多列几条，超出提示去网页端看 */
    private static final int MAX_LIST_SIZE = 15;

    /** 「最近入库」最多列几条 */
    private static final int MAX_RECENT_SIZE = 10;

    /**
     * 菜单 EventKey → 指令文本的白名单。key 必须与 {@link WeComMenuDefinition} 里建菜单用的
     * key 一致，改动时两边同步，并在企微侧重新同步一次菜单。
     * <p>
     * 「订阅」「订阅电影」刻意映射成不带关键词的指令：走到 startSearch 后会回一句
     * 「请带上要搜索的名字」，正好充当菜单点不了输入框时的引导语，不必为此单独写分支。
     */
    static final Map<String, String> MENU_COMMANDS = Map.of(
            "cmd:mysubs", "我的订阅",
            "cmd:downloading", "下载中",
            "cmd:recent", "最近入库",
            "cmd:sub_tv", "订阅",
            "cmd:sub_movie", "订阅电影",
            "cmd:help", "帮助",
            "cmd:whoami", "我的账号");

    private static final String HELP_TEXT = """
            OSR 订阅助手，可用指令：

            订阅 <剧名>       搜索剧集并订阅，如：订阅 三体
            订阅电影 <片名>   搜索电影并订阅
            我的订阅          查看自己的订阅列表
            下载中            查看正在下载的集
            最近入库          查看最近入库的集
            进度 <编号>       查看某条订阅的进度
            暂停 <编号>       暂停订阅
            恢复 <编号>       恢复订阅
            我的账号          查看绑定状态
            取消              中断当前的多轮选择
            帮助              显示本说明

            搜索后直接回复序号即可选择。""";

    @Autowired
    private IWecomUserPlusService wecomUserService;
    @Autowired
    private WeComUserProvisioner provisioner;
    @Autowired
    private OpenlistConfig config;
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
    private WeComSessionStore sessionStore;

    /**
     * 处理一条入站消息，返回要回复给该成员的文本。
     *
     * @return 回复文案；返回 null 表示这条消息不需要回复（非文本消息、事件等）
     */
    public String handle(WeComInboundMessage message) {
        if (message == null || !message.isActionable()) {
            return null;
        }
        String wecomUserId = message.fromUser();
        WecomUserPlus bind = resolveBind(wecomUserId);
        if (bind == null) {
            // 把 UserId 回显出来：管理员建绑定时要填的正是这个值，让用户直接抄给管理员，
            // 省掉「去企微后台翻通讯录找自己的 UserId」这一步
            return "你还没有绑定 OSR 账号，无法使用订阅功能。\n请把你的企业微信 UserId 提供给管理员完成绑定：\n"
                    + wecomUserId;
        }
        if (!bind.isEnabled()) {
            // 已停用是管理员的明确决定，绝不能被自动开号覆盖掉
            return "你的绑定已被管理员停用，无法使用订阅功能。";
        }
        String command = toCommand(message);
        if (command == null) {
            // 菜单 key 不在白名单里：多半是企微侧菜单没重新同步，还挂着旧版本
            log.warn("收到无法识别的企微菜单事件，EventKey={}", message.eventKey());
            return "该菜单项已失效，请让管理员到「企业微信用户」页面重新同步应用菜单。";
        }
        try {
            return dispatch(bind, command);
        } catch (Exception e) {
            log.warn("处理企微指令失败，userId={} command={}", wecomUserId, command, e);
            return "处理失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 把入站消息归一成一条指令文本：文本消息取其内容，菜单点击按 EventKey 查表。
     * <p>
     * 菜单走白名单映射而不是「EventKey 直接当指令文本用」，有两个原因：菜单是写死在
     * 企微服务器上的，与代码里的指令文案解耦后，改文案不必重新同步菜单；同时白名单本身
     * 限定了菜单能触发的动作范围，不会因为 EventKey 可控而变成任意指令入口。
     *
     * @return 指令文本；菜单 key 不认识时返回 null
     */
    private String toCommand(WeComInboundMessage message) {
        if (message.isText()) {
            return message.content().trim();
        }
        return MENU_COMMANDS.get(message.eventKey().trim());
    }

    /**
     * 取该成员的绑定；没有且开了自动开号就就地建一个。
     * <p>
     * 已存在的绑定<b>一律原样返回</b>（含已停用的），由调用方判断是否可用：
     * 停用是管理员的明确决定，若在这里当成「没绑定」去自动新建，等于任何人被停用后
     * 再发一条消息就能自己解封。
     *
     * @return 绑定；未绑定且未开自动开号（或开号失败）时返回 null
     */
    private WecomUserPlus resolveBind(String wecomUserId) {
        WecomUserPlus bind = wecomUserService.getByWecomUserId(wecomUserId);
        if (bind != null || !config.isWeComAutoCreateUser()) {
            return bind;
        }
        try {
            return provisioner.provision(wecomUserId);
        } catch (Exception e) {
            // 并发下同一成员连发两条消息，后一个事务会撞 wecom_userid 唯一索引回滚，
            // 重查一次就能拿到先到者建好的绑定
            WecomUserPlus existing = wecomUserService.getByWecomUserId(wecomUserId);
            if (existing != null) {
                return existing;
            }
            log.warn("为企微成员[{}]自动开号失败：{}", wecomUserId, e.getMessage());
            return null;
        }
    }

    private String dispatch(WecomUserPlus bind, String text) {
        String wecomUserId = bind.getWecomUserid();

        if (matches(text, "帮助", "help", "?", "？")) {
            sessionStore.clear(wecomUserId);
            return HELP_TEXT;
        }
        if (matches(text, "取消", "cancel")) {
            sessionStore.clear(wecomUserId);
            return "已取消当前操作。";
        }
        if (matches(text, "我的订阅", "订阅列表", "list")) {
            sessionStore.clear(wecomUserId);
            return listSubscriptions(bind);
        }
        if (matches(text, "下载中", "在下载", "downloading")) {
            sessionStore.clear(wecomUserId);
            return listDownloading(bind);
        }
        if (matches(text, "最近入库", "已入库", "recent")) {
            sessionStore.clear(wecomUserId);
            return listRecentInLibrary(bind);
        }
        if (matches(text, "我的账号", "我是谁", "whoami")) {
            sessionStore.clear(wecomUserId);
            return describeAccount(bind);
        }

        String movieKeyword = stripPrefix(text, "订阅电影", "订阅 电影", "电影订阅");
        if (movieKeyword != null) {
            return startSearch(wecomUserId, TmdbSearchService.TYPE_MOVIE, movieKeyword);
        }
        String tvKeyword = stripPrefix(text, "订阅剧集", "订阅 剧集", "订阅剧", "订阅 剧", "订阅");
        if (tvKeyword != null) {
            return startSearch(wecomUserId, TmdbSearchService.TYPE_TV, tvKeyword);
        }

        String progressArg = stripPrefix(text, "进度", "查看");
        if (progressArg != null) {
            return showProgress(bind, progressArg);
        }
        String pauseArg = stripPrefix(text, "暂停");
        if (pauseArg != null) {
            return switchStatus(bind, pauseArg, true);
        }
        String resumeArg = stripPrefix(text, "恢复", "启用");
        if (resumeArg != null) {
            return switchStatus(bind, resumeArg, false);
        }

        // 纯数字：只有在多轮会话进行中才有意义，否则用户多半是打错了
        Integer number = parseNumber(text);
        if (number != null) {
            return continueSession(bind, number);
        }
        return "看不懂这条指令。\n\n" + HELP_TEXT;
    }

    // ---------------- 建订阅：搜索 → 选片 → 选季 ----------------

    private String startSearch(String wecomUserId, String mediaType, String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return "请带上要搜索的名字，例如：订阅 三体";
        }
        List<TmdbSearchItem> results;
        try {
            results = tmdbSearchService.search(mediaType, keyword);
        } catch (IllegalArgumentException e) {
            return "搜索失败：" + e.getMessage();
        }
        if (results == null || results.isEmpty()) {
            sessionStore.clear(wecomUserId);
            return "没搜到「" + keyword + "」，换个关键词试试。";
        }
        List<TmdbSearchItem> candidates = results.size() > MAX_CANDIDATES
                ? results.subList(0, MAX_CANDIDATES) : results;
        sessionStore.awaitMediaSelect(wecomUserId, List.copyOf(candidates));

        StringBuilder sb = new StringBuilder("搜到以下结果，回复序号选择：\n");
        for (int i = 0; i < candidates.size(); i++) {
            TmdbSearchItem item = candidates.get(i);
            sb.append('\n').append(i + 1).append(". ").append(item.getTitle());
            if (StringUtils.isNotBlank(item.getYear())) {
                sb.append(" (").append(item.getYear()).append(')');
            }
        }
        return sb.toString();
    }

    /** 收到一个数字：按当前会话阶段决定它是「选第几部作品」还是「选第几季」 */
    private String continueSession(WecomUserPlus bind, int number) {
        String wecomUserId = bind.getWecomUserid();
        WeComSessionStore.WeComSession session = sessionStore.get(wecomUserId);
        if (session == null) {
            return "当前没有待选择的内容（超过 10 分钟会自动失效）。\n请先发起搜索，例如：订阅 三体";
        }
        if (session.stage() == WeComSessionStore.Stage.AWAIT_MEDIA) {
            return selectMedia(bind, session, number);
        }
        return selectSeason(bind, session, number);
    }

    private String selectMedia(WecomUserPlus bind, WeComSessionStore.WeComSession session, int index) {
        List<TmdbSearchItem> candidates = session.candidates();
        if (index < 1 || index > candidates.size()) {
            return "序号超出范围，请回复 1 ~ " + candidates.size() + " 之间的数字。";
        }
        TmdbSearchItem selected = candidates.get(index - 1);
        String wecomUserId = bind.getWecomUserid();

        if (TmdbSearchService.TYPE_MOVIE.equalsIgnoreCase(selected.getMediaType())) {
            sessionStore.clear(wecomUserId);
            return doSubscribe(bind, selected, null);
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
            sessionStore.clear(wecomUserId);
            return doSubscribe(bind, selected, 1);
        }
        sessionStore.awaitSeasonSelect(wecomUserId, selected, latestSeason);
        return "「" + selected.getTitle() + "」共 " + latestSeason + " 季，回复季号订阅（1 ~ " + latestSeason + "）。";
    }

    private String selectSeason(WecomUserPlus bind, WeComSessionStore.WeComSession session, int season) {
        if (season < 1 || season > session.latestSeason()) {
            return "季号超出范围，请回复 1 ~ " + session.latestSeason() + " 之间的数字。";
        }
        sessionStore.clear(bind.getWecomUserid());
        return doSubscribe(bind, session.selected(), season);
    }

    /** 真正落库。season 传 null 表示电影 */
    private String doSubscribe(WecomUserPlus bind, TmdbSearchItem item, Integer season) {
        SubscribeRequest request = new SubscribeRequest();
        request.setTmdbId(item.getTmdbId());
        request.setMediaType(item.getMediaType());
        request.setSeason(season);
        request.setOwnerUserId(bind.getSysUserId());

        PtSubscriptionPlus sub;
        try {
            sub = subscriptionBiz.subscribe(request);
        } catch (IllegalArgumentException e) {
            return "订阅失败：" + e.getMessage();
        } catch (Exception e) {
            log.error("企微指令建订阅失败，tmdbId={}", item.getTmdbId(), e);
            return "订阅失败，请稍后重试或到网页端操作。";
        }
        if (SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            try {
                searchOnCreateTrigger.triggerAsync(sub.getId());
            } catch (Exception e) {
                // 补搜只是加速首次抓取，失败不影响订阅本身，正常的 RSS 轮询照样会命中
                log.warn("订阅[{}]建订阅补搜触发失败：{}", sub.getId(), e.getMessage());
            }
        }
        return "已订阅：" + describe(sub) + "\n编号 " + sub.getId()
                + "，共 " + sub.getTotalEpisodes() + " 集"
                + (SubscriptionService.STATUS_COMPLETED.equals(sub.getStatus())
                ? "\n媒体库里已全部入库，无需下载。"
                : "\n已开始搜索资源，有进展会通知你。");
    }

    // ---------------- 查询与状态操作 ----------------

    /**
     * 当前成员可见的订阅，倒序。可见性规则与网页端一致：
     * 管理员看全部，其余人看「自己的 + 无归属的历史公共订阅」。
     *
     * @param limit 最多取几条
     */
    private List<PtSubscriptionPlus> listVisibleSubscriptions(WecomUserPlus bind, int limit) {
        LambdaQueryWrapper<PtSubscriptionPlus> wrapper = new LambdaQueryWrapper<>();
        if (!SysUser.isAdmin(bind.getSysUserId())) {
            Long ownerId = bind.getSysUserId();
            wrapper.and(w -> w.eq(PtSubscriptionPlus::getOwnerUserId, ownerId)
                    .or().isNull(PtSubscriptionPlus::getOwnerUserId));
        }
        wrapper.orderByDesc(PtSubscriptionPlus::getId);
        if (limit != Integer.MAX_VALUE) {
            wrapper.last("limit " + limit);
        }
        return subscriptionService.list(wrapper);
    }

    private String listSubscriptions(WecomUserPlus bind) {
        // 多查一条用来判断「还有更多」，不必再发一次 count 查询
        List<PtSubscriptionPlus> subs = listVisibleSubscriptions(bind, MAX_LIST_SIZE + 1);
        if (subs.isEmpty()) {
            return "你还没有订阅。发送「订阅 剧名」开始第一条。";
        }
        boolean truncated = subs.size() > MAX_LIST_SIZE;
        StringBuilder sb = new StringBuilder("你的订阅：\n");
        for (PtSubscriptionPlus sub : truncated ? subs.subList(0, MAX_LIST_SIZE) : subs) {
            sb.append('\n').append(sub.getId()).append(". ").append(describe(sub))
                    .append('（').append(statusText(sub.getStatus())).append('）');
        }
        if (truncated) {
            sb.append("\n\n仅显示最近 ").append(MAX_LIST_SIZE).append(" 条，完整列表请到网页端查看。");
        }
        return sb.toString();
    }

    /**
     * 正在下载的集，按订阅分组。
     * <p>
     * 先取用户可见的订阅、再按 subId 批量查集，而不是反过来从全部在途集里筛——
     * 后者会把别人订阅的下载动态也捞出来。
     */
    private String listDownloading(WecomUserPlus bind) {
        List<PtSubscriptionPlus> subs = listVisibleSubscriptions(bind, MAX_LIST_SIZE + 1);
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
    private String listRecentInLibrary(WecomUserPlus bind) {
        List<PtSubscriptionPlus> subs = listVisibleSubscriptions(bind, MAX_LIST_SIZE + 1);
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
    private String describeAccount(WecomUserPlus bind) {
        long subCount = listVisibleSubscriptions(bind, Integer.MAX_VALUE).size();
        return "企业微信 UserId：" + bind.getWecomUserid()
                + "\nOSR 账号：" + (StringUtils.isNotBlank(bind.getSysUserName())
                ? bind.getSysUserName() : ("#" + bind.getSysUserId()))
                + "\n可见订阅：" + subCount + " 条"
                + (SysUser.isAdmin(bind.getSysUserId()) ? "\n（管理员，可见全部订阅）" : "");
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

    private String showProgress(WecomUserPlus bind, String arg) {
        Integer subId = parseNumber(arg);
        if (subId == null) {
            return "请带上订阅编号，例如：进度 3（编号见「我的订阅」）";
        }
        PtSubscriptionPlus sub = requireAccessible(bind, subId);
        if (sub == null) {
            return "订阅不存在或无权访问。";
        }
        SubscriptionProgress progress;
        try {
            progress = subscriptionBiz.getProgress(subId);
        } catch (IllegalArgumentException e) {
            return "查询失败：" + e.getMessage();
        }
        StringBuilder sb = new StringBuilder(describe(sub))
                .append('\n').append("状态：").append(statusText(sub.getStatus()))
                .append('\n').append("已入库：").append(progress.getInLibraryCount()).append('/').append(progress.getTotalEpisodes())
                .append('\n').append("下载中：").append(progress.getInFlightCount()).append(" 集");
        List<Integer> missing = progress.getMissingEpisodes();
        if (missing != null && !missing.isEmpty()) {
            sb.append('\n').append("仍缺：").append(formatMissing(missing));
        }
        return sb.toString();
    }

    private String switchStatus(WecomUserPlus bind, String arg, boolean pause) {
        Integer subId = parseNumber(arg);
        if (subId == null) {
            return "请带上订阅编号，例如：" + (pause ? "暂停 3" : "恢复 3");
        }
        PtSubscriptionPlus sub = requireAccessible(bind, subId);
        if (sub == null) {
            return "订阅不存在或无权访问。";
        }
        try {
            if (pause) {
                subscriptionBiz.pause(subId);
            } else {
                subscriptionBiz.resume(subId);
            }
        } catch (IllegalArgumentException e) {
            return "操作失败：" + e.getMessage();
        }
        return (pause ? "已暂停：" : "已恢复：") + describe(sub);
    }

    /**
     * 取订阅并校验当前成员有权访问，规则与网页端一致（管理员全量；其余人只能碰自己的和无归属的）。
     *
     * @return 无权访问或不存在时返回 null——两种情况回同一句提示，避免变成一个探测别人订阅的接口
     */
    private PtSubscriptionPlus requireAccessible(WecomUserPlus bind, Integer subId) {
        PtSubscriptionPlus sub = subscriptionService.getById(subId);
        if (sub == null) {
            return null;
        }
        if (SysUser.isAdmin(bind.getSysUserId())
                || sub.getOwnerUserId() == null
                || sub.getOwnerUserId().equals(bind.getSysUserId())) {
            return sub;
        }
        return null;
    }

    // ---------------- 文本工具 ----------------

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
