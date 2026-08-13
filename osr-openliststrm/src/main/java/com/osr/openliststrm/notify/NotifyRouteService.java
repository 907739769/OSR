package com.osr.openliststrm.notify;

import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import com.osr.openliststrm.mybatisplus.service.INotifyRoutePlusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知路由查询，带进程内缓存。
 * <p>
 * 通知是热路径（RSS 一轮命中十几条就要发十几次），而路由表最多是
 * 通知类型数 × 渠道数（当前 5×3=15 行）。整表缓存成一个 Map，写入时整体失效，
 * 比每次发通知打一次库简单也快得多。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class NotifyRouteService {

    private final INotifyRoutePlusService routeService;

    /** key = 类型 + '|' + 渠道。volatile + 整体替换，读侧无锁 */
    private volatile Map<String, NotifyRoutePlus> cache;

    public NotifyRouteService(INotifyRoutePlusService routeService) {
        this.routeService = routeService;
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

    /** 全部路由，供配置页展示 */
    public List<NotifyRoutePlus> listAll() {
        return routeService.list();
    }

    /** 配置变更后调用，下次读取时重建缓存 */
    public void invalidate() {
        cache = null;
    }

    private Map<String, NotifyRoutePlus> snapshot() {
        Map<String, NotifyRoutePlus> local = cache;
        if (local != null) {
            return local;
        }
        Map<String, NotifyRoutePlus> built = new HashMap<>();
        try {
            for (NotifyRoutePlus route : routeService.list()) {
                if (route.getNotificationType() == null || route.getChannel() == null) {
                    continue;
                }
                built.put(key(route.getNotificationType(), route.getChannel()), route);
            }
        } catch (Exception e) {
            // 查不到路由不能让通知链路挂掉。返回空表 = 全部按「发送」处理，
            // 与 find() 的 null 语义一致，宁可多发也不静默丢
            log.warn("加载通知路由失败，本次按全部放行处理：{}", e.getMessage());
            return Map.of();
        }
        cache = built;
        return built;
    }

    private static String key(String type, String channel) {
        return type + '|' + channel;
    }
}
