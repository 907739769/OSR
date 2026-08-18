package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.calendar.PtCalendarService;
import com.osr.openliststrm.pt.calendar.dto.CalendarEntry;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 追剧日历。
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-calendar")
public class PtCalendarRestController extends BaseController {

    private final PtCalendarService calendarService;

    public PtCalendarRestController(PtCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    /**
     * 当前登录用户能否看到这条订阅。
     * <p>
     * 口径与 {@code PtSubscriptionRestController#canAccess}、{@code PtHealthRestController#canAccess}
     * 逐字一致：管理员看全部；其余人看自己的与无归属的（{@code owner_user_id IS NULL} 是本列
     * 上线前建的历史订阅，按约定对所有人可见）。
     * </p>
     * <p>
     * 日历此前<b>完全没有这层判定</b>——它是 pt_subscription 的第三个消费者，前两个都做了，
     * 唯独这里把全站所有人的剧名、海报与季集号铺进了格子里。三处口径必须一致，
     * 任何新增的消费者也要照做。
     * </p>
     */
    private boolean canAccess(PtSubscriptionPlus sub) {
        if (sub == null) {
            return false;
        }
        return SysUser.isAdmin(getUserId())
                || sub.getOwnerUserId() == null
                || sub.getOwnerUserId().equals(getUserId());
    }

    /**
     * 查询日期区间内的排播（含首尾两天）。
     */
    @GetMapping
    public Result<List<CalendarEntry>> query(
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        try {
            return Result.success(calendarService.query(start, end, this::canAccess));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
