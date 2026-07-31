package com.osr.web.controller.api.monitor;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.page.PageDomain;
import com.osr.common.core.page.TableSupport;
import com.osr.quartz.domain.SysJobLog;
import com.osr.quartz.service.ISysJobLogService;

/**
 * 定时任务日志监控REST API控制器
 *
 * @author osr
 */
@RestController
@RequestMapping("/api/monitor/jobLog")
public class MonitorJobLogApiController extends BaseController
{
    @Autowired
    private ISysJobLogService jobLogService;

    /**
     * 查询定时任务日志分页列表
     */
    @GetMapping("/list")
    public Result<PageResult<SysJobLog>> list(SysJobLog jobLog)
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Page<SysJobLog> page = new Page<>(pageDomain.getPageNum(), pageDomain.getPageSize());
        List<SysJobLog> list = jobLogService.selectJobLogListPage(page, jobLog);
        return Result.success(PageResult.of(list, page.getTotal(), (int) page.getCurrent(), (int) page.getSize()));
    }

    /**
     * 根据日志ID查询定时任务日志信息
     */
    @GetMapping("/{jobLogId}")
    public Result<SysJobLog> getInfo(@PathVariable("jobLogId") Long jobLogId)
    {
        SysJobLog jobLog = jobLogService.selectJobLogById(jobLogId);
        return Result.success(jobLog);
    }
}
