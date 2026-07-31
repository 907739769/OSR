package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.mybatisplus.domain.RenameOrphanPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameOrphanPlusService;
import com.osr.openliststrm.orphan.IRenameOrphanScanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 重命名一致性检查（孤儿清理）REST API控制器
 *
 * @author Jack
 * @date 2026-07-19
 */
@RestController
@RequestMapping("/api/openliststrm/rename-orphans")
public class RenameOrphanRestController extends BaseCrudRestController<IRenameOrphanPlusService, RenameOrphanPlus>
{
    @Autowired
    private IRenameOrphanScanService scanService;

    /**
     * 手动触发全量扫描（异步执行，立即返回）
     */
    @PostMapping("/scan")
    public Result<Void> scan()
    {
        AsyncManager.me().execute(scanService::scan);
        return Result.success();
    }

    /**
     * 批量确认清理
     */
    @PostMapping("/clean")
    public Result<Void> clean(@RequestParam("ids") String ids)
    {
        if (StringUtils.isEmpty(ids))
        {
            return Result.error("请选择要清理的记录");
        }
        scanService.clean(parseIds(ids));
        return Result.success();
    }

    /**
     * 批量忽略
     */
    @PostMapping("/ignore")
    public Result<Void> ignore(@RequestParam("ids") String ids)
    {
        if (StringUtils.isEmpty(ids))
        {
            return Result.error("请选择要忽略的记录");
        }
        scanService.ignore(parseIds(ids));
        return Result.success();
    }

    private List<Integer> parseIds(String ids)
    {
        return Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).collect(Collectors.toList());
    }

    @Override
    protected QueryWrapper<RenameOrphanPlus> buildQueryWrapper(RenameOrphanPlus entity)
    {
        QueryWrapper<RenameOrphanPlus> wrapper = new QueryWrapper<>();
        if (entity != null)
        {
            if (StringUtils.isNotEmpty(entity.getStatus()))
            {
                wrapper.eq("status", entity.getStatus());
            }
            if (StringUtils.isNotEmpty(entity.getReason()))
            {
                wrapper.eq("reason", entity.getReason());
            }
            if (StringUtils.isNotEmpty(entity.getTitle()))
            {
                wrapper.like("title", entity.getTitle());
            }
        }
        wrapper.orderByDesc("found_time");
        return wrapper;
    }
}
