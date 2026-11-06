package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.jobs.BackgroundJobService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/openliststrm/background-jobs")
public class BackgroundJobRestController extends BaseController {
    private final BackgroundJobService jobs;
    public BackgroundJobRestController(BackgroundJobService jobs) { this.jobs = jobs; }
    public record Request(BackgroundJobService.Kind kind, List<Integer> ids) {}
    @GetMapping
    public Result<List<BackgroundJobService.Job>> list() { return Result.success(jobs.list(getUserId())); }
    @GetMapping("/{id}")
    public Result<BackgroundJobService.Job> get(@PathVariable("id") String id) { return Result.success(jobs.get(id, getUserId())); }
    @PostMapping
    public Result<BackgroundJobService.Job> submit(@RequestBody Request request) {
        if (request == null || request.kind() == null) return Result.error("请选择任务类型");
        return Result.success(jobs.submit(request.kind(), request.ids(), getUserId()));
    }
    @PostMapping("/{id}/cancel")
    public Result<BackgroundJobService.Job> cancel(@PathVariable("id") String id) { return Result.success(jobs.cancel(id, getUserId())); }
    @PostMapping("/{id}/retry")
    public Result<BackgroundJobService.Job> retry(@PathVariable("id") String id) { return Result.success(jobs.retry(id, getUserId())); }
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> invalid(IllegalArgumentException error) { return Result.error(error.getMessage()); }
}
