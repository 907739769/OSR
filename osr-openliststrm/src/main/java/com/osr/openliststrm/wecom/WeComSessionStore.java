package com.osr.openliststrm.wecom;

import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企微指令的多轮会话状态：用户发「订阅 三体」拿到候选列表后，下一条消息回一个序号，
 * 得知道那个序号指的是哪一部作品。企微的文本消息不带上下文，只能由我们自己记住。
 * <p>
 * 存在内存里而不是库里：会话生命周期以分钟计，重启后让用户重发一次指令即可，
 * 为此建一张表并不划算。代价是多实例部署时会话不共享——OSR 是单实例部署，暂不考虑。
 * <p>
 * 过期清理是<b>惰性</b>的：读到过期会话即视为不存在并移除，另外在写入时顺手清一遍全表。
 * 没有后台清理线程，因为会话总量至多等于企微成员数，量级极小。
 *
 * @author Jack
 */
@Component
public class WeComSessionStore {

    /** 会话有效期。定得比人的思考时间宽松，又不至于让隔天的一个「1」误触发订阅 */
    private static final long SESSION_TTL_MILLIS = 10 * 60 * 1000L;

    private final Map<String, WeComSession> sessions = new ConcurrentHashMap<>();

    /** 取会话，不存在或已过期返回 null */
    public WeComSession get(String wecomUserId) {
        WeComSession session = sessions.get(wecomUserId);
        if (session == null) {
            return null;
        }
        if (session.isExpired()) {
            sessions.remove(wecomUserId, session);
            return null;
        }
        return session;
    }

    /** 记住「等待用户从候选列表里选一部作品」 */
    public void awaitMediaSelect(String wecomUserId, List<TmdbSearchItem> candidates) {
        put(wecomUserId, new WeComSession(Stage.AWAIT_MEDIA, candidates, null, 0));
    }

    /** 记住「等待用户选季」 */
    public void awaitSeasonSelect(String wecomUserId, TmdbSearchItem selected, int latestSeason) {
        put(wecomUserId, new WeComSession(Stage.AWAIT_SEASON, null, selected, latestSeason));
    }

    /** 会话结束（已建订阅或用户改发了别的指令） */
    public void clear(String wecomUserId) {
        sessions.remove(wecomUserId);
    }

    private void put(String wecomUserId, WeComSession session) {
        sessions.values().removeIf(WeComSession::isExpired);
        sessions.put(wecomUserId, session);
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
     * @param stage        当前阶段
     * @param candidates   搜索候选，仅 AWAIT_MEDIA 阶段有值
     * @param selected     已选定的作品，仅 AWAIT_SEASON 阶段有值
     * @param latestSeason 该剧最新季号，仅 AWAIT_SEASON 阶段有值，用于校验用户输入的季号
     */
    public record WeComSession(Stage stage, List<TmdbSearchItem> candidates, TmdbSearchItem selected,
                               int latestSeason, long expireAt) {

        WeComSession(Stage stage, List<TmdbSearchItem> candidates, TmdbSearchItem selected, int latestSeason) {
            this(stage, candidates, selected, latestSeason, System.currentTimeMillis() + SESSION_TTL_MILLIS);
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
