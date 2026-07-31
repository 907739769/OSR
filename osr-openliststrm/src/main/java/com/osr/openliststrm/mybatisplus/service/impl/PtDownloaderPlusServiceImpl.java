package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtDownloaderPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyTaskPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * <p>
 * PT 下载器配置 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
@Slf4j
@Service
public class PtDownloaderPlusServiceImpl extends ServiceImpl<PtDownloaderPlusMapper, PtDownloaderPlus> implements IPtDownloaderPlusService {

    @Autowired
    private IOpenlistCopyTaskPlusService copyTaskService;

    @Override
    public String validateSavePath(String savePath) {
        if (StringUtils.isBlank(savePath)) {
            return "保存路径不能为空";
        }
        Path target;
        try {
            target = Paths.get(savePath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            // 本方法契约是「只提示不阻断」，路径解析失败也不能抛异常拖垮保存流程
            return "保存路径格式不合法，请检查路径中是否包含非法字符";
        }
        List<OpenlistCopyTaskPlus> tasks = copyTaskService.list();
        for (OpenlistCopyTaskPlus task : tasks) {
            // 只认启用中的同步任务：停用的任务不会启动 FileMonitor，落在它目录下的文件同样不会被上传
            if (!"1".equals(task.getCopyTaskStatus())) {
                continue;
            }
            String monitorDir = task.getMonitorDir();
            if (StringUtils.isBlank(monitorDir)) {
                continue;
            }
            Path dir;
            try {
                dir = Paths.get(monitorDir).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                // 单条同步任务配置的监听目录非法，跳过它继续校验其余任务，不让一条坏配置拖垮整体校验
                log.warn("同步任务监听目录格式不合法，已跳过: {}", monitorDir, e);
                continue;
            }
            if (target.startsWith(dir)) {
                return null;
            }
        }
        return "保存路径不在任何已启用文件同步任务的监听目录之下，下载完成后不会被自动上传";
    }
}
