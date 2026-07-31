package com.osr.openliststrm.controller.api;

import com.osr.common.annotation.Anonymous;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.req.NotifyReq;
import com.osr.openliststrm.service.ICopyService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 通知回调 REST API控制器
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/notify")
@Anonymous
public class NotifyRestController
{
    private static final Logger log = LoggerFactory.getLogger(NotifyRestController.class);

    @Autowired
    private ICopyService copyService;

    @Autowired
    private OpenListHelper openListHelper;

    @Autowired
    private OpenlistConfig config;

    /**
     * 目录变更通知
     */
    @PostMapping("/notifyByDir")
    public Result<Void> notifyByDir(@RequestBody @Valid NotifyReq req, HttpServletRequest request)
    {
        log.debug("req: {}", req);
        if (StringUtils.isBlank(request.getHeader("X-API-KEY")))
        {
            log.warn("没有设置参数openlist.api.apikey");
            return Result.error("APIKEY校验不通过");
        }
        if (!java.security.MessageDigest.isEqual(
                request.getHeader("X-API-KEY").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                config.getOpenListApiKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        {
            log.warn("APIKEY校验不通过");
            return Result.error("APIKEY校验不通过");
        }
        try
        {
            // 注意：这里必须使用普通前缀截取而非正则替换。qbDlRootPath 来自外部回调输入，
            // 若使用 String#replaceFirst 会把它当作正则表达式解析，路径中含有 ( . + [ 等
            // 字符时会抛出 PatternSyntaxException，导致该匿名回调接口直接 500。
            String relativePath = StringUtils.removeStart(req.getQbDlFilePath(), req.getQbDlRootPath());
            if (openListHelper.isVideo(req.getQbDlFilePath()))
            {
                copyService.syncOneFile(req.getSrcDir(), req.getSrcDst(), relativePath);
            }
            else
            {
                copyService.syncFiles(req.getSrcDir(), req.getSrcDst(), relativePath);
            }
        }
        catch (Exception e)
        {
            log.error("处理目录变更通知失败, req: {}", req, e);
            return Result.error("处理通知失败: " + e.getMessage());
        }
        return Result.success();
    }
}
