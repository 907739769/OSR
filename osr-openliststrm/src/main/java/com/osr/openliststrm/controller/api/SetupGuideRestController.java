package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.setup.SetupGuideService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/openliststrm/setup-guide")
public class SetupGuideRestController extends BaseController {
    private final SetupGuideService guide;
    public SetupGuideRestController(SetupGuideService guide) { this.guide=guide; }
    @GetMapping
    public Result<List<SetupGuideService.Step>> status(@RequestParam(value="mode",defaultValue="STRM") String mode) {
        if (!isAdmin()) return Result.error("仅管理员可查看配置引导");
        return Result.success(guide.status(mode));
    }
    @GetMapping("/verify")
    public Result<List<SetupGuideService.Evidence>> verify(@RequestParam("fileName") String fileName) {
        if (!isAdmin()) return Result.error("仅管理员可验证文件流程");
        return Result.success(guide.verify(fileName));
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> invalid(IllegalArgumentException error) { return Result.error(error.getMessage()); }
}
