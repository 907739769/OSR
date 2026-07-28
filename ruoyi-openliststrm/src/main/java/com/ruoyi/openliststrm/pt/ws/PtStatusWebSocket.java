package com.ruoyi.openliststrm.pt.ws;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.JwtTokenUtil;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅/下载记录状态实时推送 WebSocket。
 * <p>
 * 只做 token 合法性校验（与 PT 模块现有 REST 接口"登录即可用"的门槛对齐，不做细粒度权限校验），
 * 连接建立后不推历史消息——REST 列表接口已提供全量快照，这里只负责快照之后的增量变化。
 * </p>
 * <p>
 * jakarta.websocket 容器为每个连接新建一个端点类实例，不是 Spring 单例语义：
 * {@code @Autowired} 字段只在 Spring 自己创建的那个单例 bean 实例上生效，容器为每个连接
 * 创建的实例该字段是 null。因此用 {@code @PostConstruct} 把依赖转存成静态字段，所有实例
 * （包括连接实例）统一读静态字段——写法与 {@code LogWebSocket} 完全一致。
 * </p>
 * <p>
 * 广播方法 {@link #pushDownloadEvent}/{@link #pushSubscriptionEvent} 不依赖任何 Spring bean
 * （{@code SESSIONS} 是自己的静态字段，序列化用 FastJSON2 静态 API），单测环境下裸调用也不会
 * 抛异常（集合为空就是空跑一轮 for 循环）；内部对每个 session 单独 try/catch，一个连接异常
 * （网络抖动、慢客户端）只记 debug 日志并从集合摘除，不影响其余连接，更不会向上抛出影响调用方
 * （{@code DownloadTrackService}/{@code SubscriptionEngine}）的状态推进主流程。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@ServerEndpoint("/websocket/pt/status")
@Component
public class PtStatusWebSocket {

    private static JwtTokenUtil jwtTokenUtil;

    @Autowired
    private JwtTokenUtil tokenUtil;

    @PostConstruct
    void init() {
        jwtTokenUtil = tokenUtil;
    }

    static {
        log.info(">>> PtStatusWebSocket class loaded, @ServerEndpoint=/websocket/pt/status");
    }

    /** 所有已连接的会话，跨实例共享（每个连接对应一个容器实例，见类注释） */
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();

    @OnOpen
    public void onOpen(Session session) {
        String token = extractToken(session.getQueryString());
        boolean tokenValid = token != null;
        if (tokenValid) {
            try {
                tokenValid = !jwtTokenUtil.isTokenExpired(token);
            } catch (Exception e) {
                tokenValid = false;
            }
        }
        if (!tokenValid) {
            try {
                session.getBasicRemote().sendText("unauthorized");
                session.close();
            } catch (Exception e) {
                log.debug("关闭 WebSocket 连接时出错", e);
            }
            log.warn("PT 状态推送 WebSocket 连接被拒绝：token 无效或已过期");
            return;
        }
        SESSIONS.add(session);
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        SESSIONS.remove(session);
    }

    private String extractToken(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        for (String param : queryString.split("&")) {
            int idx = param.indexOf('=');
            if (idx > 0 && "token".equals(param.substring(0, idx))) {
                return param.substring(idx + 1);
            }
        }
        return null;
    }

    /**
     * 推送下载记录状态变化。{@code progress}/{@code failReason} 按需传 null，由调用方保证：
     * DOWNLOADING 传 progress、COMPLETED 传 progress=1.0、FAILED 传 failReason，其余传 null。
     */
    public static void pushDownloadEvent(PtDownloadRecordPlus record, String state, Double progress, String failReason) {
        JSONObject json = new JSONObject();
        json.put("type", "download");
        json.put("downloadId", record.getId());
        json.put("subId", record.getSubId());
        json.put("episode", record.getEpisode());
        json.put("state", state);
        if (progress != null) {
            json.put("progress", progress);
        }
        if (failReason != null) {
            json.put("failReason", failReason);
        }
        broadcast(json.toJSONString());
    }

    /** 推送订阅命中时间变化 */
    public static void pushSubscriptionEvent(PtSubscriptionPlus sub) {
        JSONObject json = new JSONObject();
        json.put("type", "subscription");
        json.put("subId", sub.getId());
        json.put("lastMatchTime", sub.getLastMatchTime() == null
                ? null : DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, sub.getLastMatchTime()));
        broadcast(json.toJSONString());
    }

    /**
     * 遍历当前所有连接逐个发送；单个 session 发送失败（客户端已断开等）只记 debug 日志并从集合
     * 摘除、主动关闭该连接，不影响其余 session 收到消息，方法本身不抛出受检异常，调用方无需包 try/catch。
     * <p>
     * 本方法会被多个独立线程并发调用（{@code DownloadTrackTask} 轮询、{@code RssPollTask} 轮询、
     * 搜索补集、下载记录重试等 REST 请求线程），而 {@code Session#getBasicRemote()} 返回的
     * {@code RemoteEndpoint.Basic} 不是线程安全的——同一 session 若被两个线程同时调用
     * {@code sendText}，容器会抛 {@code IllegalStateException}。因此对每个 session 的发送用
     * {@code synchronized(session)} 串行化，与 {@code LogWebSocket} 那种"单线程 tail 写"模型不同，
     * 不能省略这层同步。
     * </p>
     */
    private static void broadcast(String message) {
        for (Session session : SESSIONS) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.getBasicRemote().sendText(message);
                    }
                }
            } catch (Exception e) {
                log.debug("PT 状态推送发送失败，已移除并关闭该连接：{}", e.getMessage());
                SESSIONS.remove(session);
                try {
                    session.close();
                } catch (Exception closeEx) {
                    log.debug("关闭失效 WebSocket 连接时出错", closeEx);
                }
            }
        }
    }
}
