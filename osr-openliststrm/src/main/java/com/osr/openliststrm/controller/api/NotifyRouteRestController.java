package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import com.osr.openliststrm.mybatisplus.service.INotifyRoutePlusService;
import com.osr.openliststrm.notify.INotifier;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyRouteService;
import com.osr.openliststrm.notify.dto.NotifyMatrix;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通知路由配置：通知类型 × 渠道 × 收件人范围。
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/notify-routes")
public class NotifyRouteRestController {

    private static final Set<String> VALID_SCOPES = Set.of(
            NotifyRoutePlus.SCOPE_ADMIN, NotifyRoutePlus.SCOPE_OWNER, NotifyRoutePlus.SCOPE_BOTH);

    private final INotifyRoutePlusService routeService;
    private final NotifyRouteService routeCache;
    private final List<INotifier> notifiers;

    public NotifyRouteRestController(INotifyRoutePlusService routeService, NotifyRouteService routeCache,
                                     List<INotifier> notifiers) {
        this.routeService = routeService;
        this.routeCache = routeCache;
        this.notifiers = notifiers;
    }

    /**
     * 配置页所需的全部数据。类型与渠道清单都由后端给，前端不重复维护一份枚举——
     * 新增渠道时只要实现类注册进 Spring，页面自动多出一列。
     */
    @GetMapping("/matrix")
    public Result<NotifyMatrix> matrix() {
        List<NotifyMatrix.TypeMeta> types = Arrays.stream(NotificationType.values())
                .map(t -> new NotifyMatrix.TypeMeta(t.name(), t.getLabel()))
                .toList();
        List<NotifyMatrix.ChannelMeta> channels = notifiers.stream()
                .map(n -> new NotifyMatrix.ChannelMeta(n.channelKey(), n.displayName(),
                        n.supportsDirectDelivery(), n.isConfigured()))
                .sorted(java.util.Comparator.comparing(NotifyMatrix.ChannelMeta::key))
                .toList();
        List<NotifyMatrix.RouteItem> routes = routeService.list().stream()
                .map(r -> new NotifyMatrix.RouteItem(r.getNotificationType(), r.getChannel(),
                        r.enabledOn(), r.getRecipientScope()))
                .toList();
        return Result.success(new NotifyMatrix(types, channels, routes));
    }

    /**
     * 整表保存。前端一次提交完整矩阵，后端按 (类型, 渠道) upsert。
     * <p>
     * 整表提交而不是逐格 PATCH：矩阵格子多，逐格提交会产生一串请求，中途失败还会留下
     * 半保存状态；而这张表最多几十行，整表覆盖简单且没有中间态。
     * </p>
     */
    @PostMapping
    public Result<Void> save(@RequestBody List<NotifyMatrix.RouteItem> items) {
        if (items == null || items.isEmpty()) {
            return Result.error("没有要保存的配置");
        }
        Set<String> validTypes = Arrays.stream(NotificationType.values())
                .map(Enum::name).collect(Collectors.toSet());
        Set<String> validChannels = notifiers.stream().map(INotifier::channelKey).collect(Collectors.toSet());

        List<NotifyRoutePlus> toSave = new ArrayList<>();
        List<NotifyRoutePlus> toUpdate = new ArrayList<>();
        for (NotifyMatrix.RouteItem item : items) {
            // 校验取值合法：类型/渠道来自后端下发的清单，非法值只可能是脏请求
            if (!validTypes.contains(item.notificationType()) || !validChannels.contains(item.channel())) {
                return Result.error("非法的通知类型或渠道：" + item.notificationType() + "/" + item.channel());
            }
            String scope = VALID_SCOPES.contains(item.recipientScope())
                    ? item.recipientScope() : NotifyRoutePlus.SCOPE_ADMIN;

            NotifyRoutePlus existing = routeService.getOne(new LambdaQueryWrapper<NotifyRoutePlus>()
                    .eq(NotifyRoutePlus::getNotificationType, item.notificationType())
                    .eq(NotifyRoutePlus::getChannel, item.channel()), false);
            if (existing == null) {
                NotifyRoutePlus entity = new NotifyRoutePlus();
                entity.setNotificationType(item.notificationType());
                entity.setChannel(item.channel());
                entity.setEnabled(item.enabled() ? "1" : "0");
                entity.setRecipientScope(scope);
                toSave.add(entity);
            } else {
                existing.setEnabled(item.enabled() ? "1" : "0");
                existing.setRecipientScope(scope);
                toUpdate.add(existing);
            }
        }
        if (!toSave.isEmpty()) {
            routeService.saveBatch(toSave);
        }
        if (!toUpdate.isEmpty()) {
            routeService.updateBatchById(toUpdate);
        }
        // 缓存必须在写库之后失效，否则并发读会把旧值又灌回缓存
        routeCache.invalidate();
        return Result.success();
    }
}
