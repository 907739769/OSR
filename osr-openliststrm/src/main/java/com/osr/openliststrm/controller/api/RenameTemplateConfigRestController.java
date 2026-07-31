package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.rename.config.IRenameTemplateConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 重命名文件名模板配置 REST API 控制器
 *
 * @author Jack
 * @date 2026-07-20
 */
@RestController
@RequestMapping("/api/openliststrm/rename-config")
public class RenameTemplateConfigRestController {

    @Autowired
    private IRenameTemplateConfigService templateConfigService;

    /**
     * 获取当前生效的文件名模板
     */
    @GetMapping("/template")
    public Result<Map<String, String>> getTemplate() {
        Map<String, String> data = new HashMap<>();
        data.put("template", templateConfigService.getTemplate());
        return Result.success(data);
    }

    /**
     * 试渲染预览（不落库），供页面实时预览
     */
    @PostMapping("/template/preview")
    public Result<String> preview(@RequestBody Map<String, String> body) {
        String template = body.get("template");
        if (StringUtils.isEmpty(template)) {
            return Result.error("模板不能为空");
        }
        try {
            return Result.success(templateConfigService.previewRender(template));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 保存模板（校验通过才写库）
     */
    @PutMapping("/template")
    public Result<Void> updateTemplate(@RequestBody Map<String, String> body) {
        String template = body.get("template");
        if (StringUtils.isEmpty(template)) {
            return Result.error("模板不能为空");
        }
        try {
            templateConfigService.saveTemplate(template);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
