package com.osr.openliststrm.chat;

import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 聊天指令的多轮会话状态：用户发「订阅 三体」拿到候选列表后，下一条消息回一个序号，
 * 得知道那个序号指的是哪一部作品。企微/TG 的文本消息都不带上下文，只能由我们自己记住。
 * <p>
 * 键是渠道前缀加渠道内的用户标识（{@code wecom:zhangsan}、{@code tg:123456}），
 * 两个渠道的会话互不干扰。
 * <p>
 * 存在内存里而不是库里：会话生命周期以分钟计，重启后让用户重发一次指令即可，
 * 为此建一张表并不划算。代价是多实例部署时会话不共享——OSR 是单实例部署，暂不考虑。
 * <p>
 * 过期清理是<b>惰性</b>的：读到过期会话即视为不存在并移除，另外在写入时顺手清一遍全表。
 * 没有后台清理线程，因为会话总量至多等于聊天用户数，量级极小。
 *
 * @author Jack
 */
@Component
public class ChatSessionStore {

    /** 会话有效期。定得比人的思考时间宽松，又不至于让隔天的一个「1」误触发订阅 */
    private static final long SESSION_TTL_MILLIS = 10 * 60 * 1000L;

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /** 取会话，不存在或已过期返回 null */
    public ChatSession get(String sessionKey) {
        ChatSession session = sessions.get(sessionKey);
        if (session == null) {
            return null;
        }
        if (session.isExpired()) {
            sessions.remove(sessionKey, session);
            return null;
        }
        return session;
    }

    /** 记住「等待用户从候选列表里选一部作品」，返回新会话（其 id 用来识别按钮是否过期） */
    public ChatSession awaitMediaSelect(String sessionKey, List<TmdbSearchItem> candidates) {
        return put(sessionKey, new ChatSession(newId(), Stage.AWAIT_MEDIA, candidates, null, 0));
    }

    /** 记住「等待用户选季」 */
    public ChatSession awaitSeasonSelect(String sessionKey, TmdbSearchItem selected, int latestSeason) {
        return put(sessionKey, new ChatSession(newId(), Stage.AWAIT_SEASON, null, selected, latestSeason));
    }

    /** 会话结束（已建订阅或用户改发了别的指令） */
    public void clear(String sessionKey) {
        sessions.remove(sessionKey);
    }

    private ChatSession put(String sessionKey, ChatSession session) {
        sessions.values().removeIf(ChatSession::isExpired);
        sessions.put(sessionKey, session);
        return session;
    }

    /** 短随机 id，只用于区分同一用户先后两轮会话，不需要全局唯一 */
    private static String newId() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(0x100000, 0x1000000), 36);
    }

    /** 会话所处的阶段 */
    public enum Stage {
        /** 已给出搜索候选，等用户回序号选作品 */
        AWAIT_MEDIA,
        /** 已选定剧集，等用户回序号选季 */
        AWAIT_SEASON
    }

    /**
     * 一次多轮交互的中间状态。
     *
     * @param id           本轮会话的标识，写进 TG 按钮里，点到上一轮留下的旧按钮时据此识别出来
     * @param stage        当前阶段
     * @param candidates   搜索候选，仅 AWAIT_MEDIA 阶段有值
     * @param selected     已选定的作品，仅 AWAIT_SEASON 阶段有值
     * @param latestSeason 该剧最新季号，仅 AWAIT_SEASON 阶段有值，用于校验用户输入的季号
     */
    public record ChatSession(String id, Stage stage, List<TmdbSearchItem> candidates, TmdbSearchItem selected,
                              int latestSeason, long expireAt) {

        ChatSession(String id, Stage stage, List<TmdbSearchItem> candidates, TmdbSearchItem selected, int latestSeason) {
            this(id, stage, candidates, selected, latestSeason, System.currentTimeMillis() + SESSION_TTL_MILLIS);
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
