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
     * 获取当前生效的文件名模板。
     * <p>
     * 连内置默认模板一起返回，供页面上的「恢复默认」用：这个配置项已从参数设置页隐藏
     * （20260753 把它从 sys_config 里删掉了），用户把模板改坏之后原先没有任何界面途径改得回来。
     */
    @GetMapping("/template")
    public Result<Map<String, Object>> getTemplate() {
        Map<String, Object> data = new HashMap<>();
        data.put("template", templateConfigService.getTemplate());
        data.put("defaultTemplate", IRenameTemplateConfigService.DEFAULT_TEMPLATE);
        data.put("variables", templateConfigService.templateVariables());
        return Result.success(data);
    }

    /**
     * 试渲染预览（不落库），供页面实时预览；电影样例与剧集样例各渲染一份
     */
    @PostMapping("/template/preview")
    public Result<Map<String, String>> preview(@RequestBody Map<String, String> body) {
        String template = body.get("template");
        if (StringUtils.isEmpty(template)) {
            return Result.error("模板不能为空");
        }
        try {
            return Result.success(templateConfigService.previewSamples(template));
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
