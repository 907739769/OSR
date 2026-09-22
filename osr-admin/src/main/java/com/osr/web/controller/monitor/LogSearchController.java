package com.osr.web.controller.monitor;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.system.service.ISysMenuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 实时日志页的「检索历史」：在日志文件（含已滚动的分片）里按关键字 / 级别 / 时间范围检索。
 *
 * <p>授权口径与 {@link LogWebSocket} 一致：管理员，或拥有 {@code monitor:log:view} 权限。
 * 框架只做认证不做授权（见 osr-framework/AGENTS.md），不在这里判的话任何登录用户都能读全量日志。
 */
@RestController
@RequestMapping("/api/monitor/log")
public class LogSearchController extends BaseController {

    private static final String REQUIRED_PERM = "monitor:log:view";

    @Autowired
    private LogSearchService searchService;

    @Autowired
    private ISysMenuService menuService;

    /** 本项目编译不带 -parameters，@RequestParam 必须显式写参数名，否则运行时 500 */
    @GetMapping("/search")
    public Result<LogSearchService.SearchResult> search(
            @RequestParam(value = "type", defaultValue = "all") String type,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "regex", defaultValue = "false") boolean regex,
            @RequestParam(value = "levels", required = false) String levels,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", defaultValue = "0") int limit) throws IOException {
        if (!isAdmin() && !menuService.selectPermsByUserId(getUserId()).contains(REQUIRED_PERM)) {
            return Result.error(403, "缺少权限 " + REQUIRED_PERM);
        }
        try {
            return Result.success(searchService.search(new LogSearchService.Query(
                    type, keyword, regex, LogWebSocket.LevelFilter.parse(levels), from, to, limit)));
        } catch (LogSearchService.InvalidQueryException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
