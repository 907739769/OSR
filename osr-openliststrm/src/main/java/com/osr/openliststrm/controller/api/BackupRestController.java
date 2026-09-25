package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.backup.BackupRestoreException;
import com.osr.openliststrm.backup.ConfigBackupService;
import com.osr.openliststrm.backup.SubscriptionRestorer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 配置备份与恢复。
 * <p>
 * <b>四个端点全部限管理员</b>，读也不例外：导出可以带出全部密码与 API Key；预览会把备份文件
 * 与本机配置逐项对照，等于把本机有哪些下载器、索引器、订阅原样列给调用方看。
 * <p>
 * 备份文件以<b>原文</b>收发（上传的是文件本身、导出返回全文字符串），不经 JSON 转换器二次处理：
 * 否则 null 值会不会被丢掉取决于转换器配置，导出的文件与恢复时读到的就可能不是同一份东西。
 * <p>
 * <b>上传走 multipart 而不是 JSON 请求体</b>：{@code RequestLogFilter} 在 DEBUG 下会把 JSON 请求体的
 * 前 1000 字打进访问日志，而含敏感信息的备份开头就是参数设置里的各种 Token；multipart 那里只记一句
 * {@code [FILE_UPLOAD]}。
 *
 * @author Jack
 */
@Slf4j
@RestController
@RequestMapping("/api/openliststrm/backup")
public class BackupRestController extends BaseController {

    @Autowired
    private ConfigBackupService backupService;

    /** 导出备份文件全文 */
    @GetMapping("/export")
    public Result<String> export(@RequestParam(value = "includeSecrets", defaultValue = "false") boolean includeSecrets) {
        Result<String> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        return Result.success(backupService.export(includeSecrets));
    }

    /** 试算恢复，不写库 */
    @PostMapping("/preview")
    public Result<ConfigBackupService.BackupPreview> preview(@RequestParam("file") MultipartFile file) {
        Result<ConfigBackupService.BackupPreview> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            return Result.success(backupService.preview(read(file)));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 按所选分区恢复。
     *
     * @param sections 逗号分隔的分区键
     */
    @PostMapping("/restore")
    public Result<ConfigBackupService.RestoreResult> restore(@RequestParam("sections") String sections,
                                                             @RequestParam("file") MultipartFile file) {
        Result<ConfigBackupService.RestoreResult> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        Set<String> keys = Arrays.stream(sections.split(","))
                .map(String::trim).filter(StringUtils::isNotEmpty).collect(Collectors.toSet());
        try {
            return Result.success(backupService.restore(read(file), keys));
        } catch (IllegalArgumentException | BackupRestoreException e) {
            log.warn("恢复配置备份失败，已全部回滚：{}", e.getMessage());
            return Result.error("恢复失败，没有做任何修改：" + e.getMessage());
        }
    }

    /** 后台订阅恢复的进度；从没跑过时 data 为 null */
    @GetMapping("/restore/status")
    public Result<SubscriptionRestorer.RestoreStatus> restoreStatus() {
        Result<SubscriptionRestorer.RestoreStatus> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        return Result.success(backupService.subscriptionRestoreStatus());
    }

    private static String read(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取上传的备份文件失败：" + e.getMessage(), e);
        }
    }
}
