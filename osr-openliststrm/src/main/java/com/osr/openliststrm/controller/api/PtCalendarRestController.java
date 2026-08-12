package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
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
public class PtCalendarRestController {

    private final PtCalendarService calendarService;

    public PtCalendarRestController(PtCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    /**
     * 查询日期区间内的排播（含首尾两天）。
     */
    @GetMapping
    public Result<List<CalendarEntry>> query(
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        try {
            return Result.success(calendarService.query(start, end));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
