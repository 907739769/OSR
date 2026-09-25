package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.dashboard.TodoSignalService;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页「待办提醒」的后端信号。与 {@code OpenlistDashboardRestController} 分开，是因为这里要按
 * 当前用户做归属隔离（继承 {@link BaseController} 拿 isAdmin / userId），那个控制器不需要。
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/dashboard")
public class DashboardTodoRestController extends BaseController {

    @Autowired
    private TodoSignalService todoSignalService;

    @GetMapping("/todo-signals")
    public Result<TodoSignalService.TodoSignals> todoSignals() {
        return Result.success(todoSignalService.signals(PtStatsScope.of(isAdmin(), getUserId())));
    }
}
