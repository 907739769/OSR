package com.osr.openliststrm.notify;

import com.osr.common.exception.ServiceException;
import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import com.osr.openliststrm.mybatisplus.service.INotifyRoutePlusService;
import com.osr.openliststrm.notify.dto.NotifyMatrix;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通知路由：配置页的读写，以及分发时的查询（带进程内缓存）。
 * <p>
 * 通知是热路径（RSS 一轮命中十几条就要发十几次），而路由表最多是
 * 通知类型数 × 渠道数（当前 9×5=45 行）。整表缓存成一个 Map，写入时整体失效，
 * 比每次发通知打一次库简单也快得多。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class NotifyRouteService {

    private static final Set<String> VALID_SCOPES = Set.of(
            NotifyRoutePlus.SCOPE_ADMIN, NotifyRoutePlus.SCOPE_OWNER, NotifyRoutePlus.SCOPE_BOTH);

    /** 测试消息正文。按 TG 的 HTML parse_mode 写，其余渠道由各自的 toPlainText 还原 */
    static final String TEST_MESSAGE = "🔔 <b>OSR 测试通知</b>\n这是一条从「通知路由」页发出的测试消息，收到即说明该渠道配置可用。";

    private final INotifyRoutePlusService routeService;
    private final List<INotifier> notifiers;
    private final TransactionOperations tx;

    /** key = 类型 + '|' + 渠道。volatile + 整体替换，读侧无锁 */
    private volatile Map<String, NotifyRoutePlus> cache;

    /**
     * 缓存代数，每次失效 +1。重建缓存前记下代数，写回时代数没变才写——
     * 否则一个在保存之前就开始查库的读者，会在 {@link #invalidate()} 之后把旧数据写回缓存，
     * 旧配置一直生效到下次保存（用户关掉的通知照发）。写回与失效都在同一把锁里，
     * 「比较代数」和「写缓存」之间不会插进一次失效。
     */
    private long generation;

    public NotifyRouteService(INotifyRoutePlusService routeService, List<INotifier> notifiers,
                              TransactionOperations tx) {
        this.routeService = routeService;
        this.notifiers = notifiers;
        this.tx = tx;
    }

    /**
     * 查某个类型在某个渠道上的路由。
     *
     * @return 配置行；<b>表里没有该组合时返回 null</b>，调用方按「发送」处理，
     *         语义与改造前的「types 留空即不过滤」一致。新增通知类型或新增渠道后
     *         路由表来不及补行时，宁可多发也不要静默丢通知
     */
    public NotifyRoutePlus find(NotificationType type, String channelKey) {
        if (type == null || channelKey == null) {
            return null;
        }
        return snapshot().get(key(type.name(), channelKey));
    }

    /**
     * 配置页所需的全部数据。类型与渠道清单都由后端给，前端不重复维护一份枚举——
     * 新增渠道时只要实现类注册进 Spring，页面自动多出一列。
     * <p>
     * <b>路由按「类型 × 渠道」补齐后再下发</b>，缺行的格子填 {@link #defaultRoute}。
     * 补默认值原先是前端做的，填的是「仅管理员」，而后端缺行时的真实行为是「原样投递」
     * （等同仅订阅人）——页面显示与实际发送对不上，用户改别处顺手一保存，
     * 这一格就真的变成只发管理员，订阅人从此收不到。默认值只能有一份，而且必须
     * 和 {@link NotifierManager} 的缺行行为出自同一处。
     * </p>
     */
    public NotifyMatrix matrix() {
        List<NotifyMatrix.TypeMeta> types = Arrays.stream(NotificationType.values())
                .map(t -> new NotifyMatrix.TypeMeta(t.name(), t.getLabel(), t.getDescription(), t.urgent()))
                .toList();
        List<INotifier> sorted = sortedNotifiers();
        List<NotifyMatrix.ChannelMeta> channels = sorted.stream()
                .map(n -> new NotifyMatrix.ChannelMeta(n.channelKey(), n.displayName(),
                        n.supportsDirectDelivery(), n.isConfigured()))
                .toList();

        Map<String, NotifyRoutePlus> existing = loadAll();
        List<NotifyMatrix.RouteItem> routes = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            for (INotifier notifier : sorted) {
                NotifyRoutePlus row = existing.get(key(type.name(), notifier.channelKey()));
                routes.add(row != null
                        ? new NotifyMatrix.RouteItem(type.name(), notifier.channelKey(), row.enabledOn(),
                                row.getRecipientScope())
                        : defaultRoute(type, notifier));
            }
        }
        return new NotifyMatrix(types, channels, routes);
    }

    /**
     * 表里缺行时该格子的<b>实际</b>发送行为：开启；收件人对支持分人的渠道是「仅订阅人」。
     * <p>
     * 后者对应 {@link NotifierManager#applyScope} 在路由为 null 时的「原样投递」：
     * 调用方给的目标只有 {@code BROADCAST} 与 {@code owner(x)} 两种形态，
     * 与 {@code SCOPE_OWNER} 的改写结果逐一相同（无归属同样回退默认接收人）。
     * </p>
     */
    static NotifyMatrix.RouteItem defaultRoute(NotificationType type, INotifier notifier) {
        return new NotifyMatrix.RouteItem(type.name(), notifier.channelKey(), true,
                notifier.supportsDirectDelivery() ? NotifyRoutePlus.SCOPE_OWNER : NotifyRoutePlus.SCOPE_ADMIN);
    }

    /**
     * 整表保存：按 (类型, 渠道) upsert，一个事务里完成。
     * <p>
     * 整表提交而不是逐格 PATCH：矩阵格子多，逐格提交会产生一串请求，中途失败还会留下
     * 半保存状态。「没有中间态」这一点靠的是事务——插入与更新是两批语句，不包事务的话
     * 第二批失败时第一批已经生效了。
     * </p>
     * <p>
     * 缓存在<b>事务提交之后</b>才失效：放在事务里面的话，失效与提交之间的读者会按新代数
     * 把尚未提交的旧数据写回缓存，代数校验也拦不住。
     * </p>
     *
     * @throws ServiceException 提交里有非法的类型或渠道（只可能是脏请求），此时一行都不写
     */
    public void saveAll(List<NotifyMatrix.RouteItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ServiceException("没有要保存的配置");
        }
        Set<String> validTypes = new HashSet<>();
        for (NotificationType t : NotificationType.values()) {
            validTypes.add(t.name());
        }
        Set<String> validChannels = new HashSet<>();
        for (INotifier n : notifiers) {
            validChannels.add(n.channelKey());
        }
        for (NotifyMatrix.RouteItem item : items) {
            if (!validTypes.contains(item.notificationType()) || !validChannels.contains(item.channel())) {
                throw new ServiceException("非法的通知类型或渠道：" + item.notificationType() + "/" + item.channel());
            }
        }

        tx.executeWithoutResult(status -> {
            // 一次读全表再在内存里配对。原先每格一条 getOne，9×5 的矩阵就是 45 次查询
            Map<String, NotifyRoutePlus> existing = loadAll();
            List<NotifyRoutePlus> toSave = new ArrayList<>();
            List<NotifyRoutePlus> toUpdate = new ArrayList<>();
            for (NotifyMatrix.RouteItem item : items) {
                String scope = VALID_SCOPES.contains(item.recipientScope())
                        ? item.recipientScope() : NotifyRoutePlus.SCOPE_ADMIN;
                String enabled = item.enabled() ? "1" : "0";
                NotifyRoutePlus row = existing.get(key(item.notificationType(), item.channel()));
                if (row == null) {
                    row = new NotifyRoutePlus();
                    row.setNotificationType(item.notificationType());
                    row.setChannel(item.channel());
                    row.setEnabled(enabled);
                    row.setRecipientScope(scope);
                    toSave.add(row);
                } else if (!enabled.equals(row.getEnabled()) || !scope.equals(row.getRecipientScope())) {
                    row.setEnabled(enabled);
                    row.setRecipientScope(scope);
                    toUpdate.add(row);
                }
            }
            if (!toSave.isEmpty()) {
                routeService.saveBatch(toSave);
            }
            if (!toUpdate.isEmpty()) {
                routeService.updateBatchById(toUpdate);
            }
        });
        invalidate();
    }

    /**
     * 向某个渠道发一条测试消息，如实返回结果。
     * <p>
     * 页面上「已配置」只说明 token/地址填了，不说明填对了；而用户原先唯一的验证手段是
     * 干等下一条真实通知——等不来时分不清是没触发还是发不出去。测试消息<b>绕过路由</b>
     * （问的是「这个渠道通不通」，与哪个类型开没开无关），支持分人的渠道发给默认接收人。
     * </p>
     *
     * @return null 表示发送成功，否则是给用户看的失败原因
     */
    public String sendTest(String channelKey) {
        INotifier notifier = notifiers.stream()
                .filter(n -> n.channelKey().equals(channelKey))
                .findFirst()
                .orElseThrow(() -> new ServiceException("未知的通知渠道：" + channelKey));
        if (!notifier.isConfigured()) {
            return notifier.displayName() + " 尚未配置，请先到「参数设置」里填写";
        }
        try {
            String reason = notifier.sendTest(TEST_MESSAGE);
            if (reason != null) {
                log.warn("通知渠道[{}]测试发送失败：{}", channelKey, reason);
            } else {
                log.info("通知渠道[{}]测试发送成功", channelKey);
            }
            return reason;
        } catch (Exception e) {
            log.warn("通知渠道[{}]测试发送异常：{}", channelKey, e.getMessage(), e);
            return "发送异常：" + e.getMessage();
        }
    }

    /** 配置变更后调用，下次读取时重建缓存 */
    public void invalidate() {
        synchronized (this) {
            generation++;
            cache = null;
        }
    }

    private Map<String, NotifyRoutePlus> snapshot() {
        Map<String, NotifyRoutePlus> local = cache;
        if (local != null) {
            return local;
        }
        long startGeneration;
        synchronized (this) {
            startGeneration = generation;
        }
        Map<String, NotifyRoutePlus> built;
        try {
            built = loadAll();
        } catch (Exception e) {
            // 查不到路由不能让通知链路挂掉。返回空表 = 全部按「发送」处理，
            // 与 find() 的 null 语义一致，宁可多发也不静默丢
            log.warn("加载通知路由失败，本次按全部放行处理：{}", e.getMessage(), e);
            return Map.of();
        }
        synchronized (this) {
            if (generation == startGeneration) {
                cache = built;
            }
        }
        return built;
    }

    private Map<String, NotifyRoutePlus> loadAll() {
        Map<String, NotifyRoutePlus> map = new HashMap<>();
        for (NotifyRoutePlus route : routeService.list()) {
            if (route.getNotificationType() == null || route.getChannel() == null) {
                continue;
            }
            map.put(key(route.getNotificationType(), route.getChannel()), route);
        }
        return map;
    }

    private List<INotifier> sortedNotifiers() {
        return notifiers.stream().sorted(Comparator.comparing(INotifier::channelKey)).toList();
    }

    private static String key(String type, String channel) {
        return type + '|' + channel;
    }
}
