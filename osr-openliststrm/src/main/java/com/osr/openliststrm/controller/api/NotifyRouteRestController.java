package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.openliststrm.notify.NotifyRouteService;
import com.osr.openliststrm.notify.dto.NotifyMatrix;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知路由配置：通知类型 × 渠道 × 收件人范围。读写逻辑都在 {@link NotifyRouteService}。
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/notify-routes")
public class NotifyRouteRestController {

    private final NotifyRouteService routeService;

    public NotifyRouteRestController(NotifyRouteService routeService) {
        this.routeService = routeService;
    }

    /** 配置页所需的全部数据，路由已按「类型 × 渠道」补齐 */
    @GetMapping("/matrix")
    public Result<NotifyMatrix> matrix() {
        return Result.success(routeService.matrix());
    }

    /** 整表保存，前端一次提交完整矩阵 */
    @PostMapping
    public Result<Void> save(@RequestBody List<NotifyMatrix.RouteItem> items) {
        routeService.saveAll(items);
        return Result.success();
    }

    /**
     * 向某个渠道发一条测试消息。发送失败时返回 error 并带上具体原因，
     * 页面直接展示给用户——「发送失败」四个字不帮他判断是 token 错了还是网络不通。
     */
    @PostMapping("/test/{channel}")
    public Result<Void> test(@PathVariable("channel") String channel) {
        String reason = routeService.sendTest(channel);
        return reason == null ? Result.success() : Result.error(reason);
    }
}
