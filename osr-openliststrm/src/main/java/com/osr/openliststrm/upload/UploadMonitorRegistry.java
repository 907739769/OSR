package com.osr.openliststrm.upload;

import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.monitor.WatchServiceMonitor;
import com.osr.openliststrm.monitor.processor.MediaUploadProcessor;
import com.osr.openliststrm.monitor.service.FileMonitorCoordinator;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyTaskPlus;
import com.osr.openliststrm.service.ICopyService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重命名监控注册表
 *
 * @author: Jack
 * @creat: 2026/1/13 11:07
 */
@Slf4j
public class UploadMonitorRegistry {

    private static class MonitorInfo {
        final FileMonitorCoordinator service;
        final String copyTaskSrc;
        final String copyTaskDst;
        final String monitorDir;

        MonitorInfo(FileMonitorCoordinator service, String copyTaskSrc, String copyTaskDst, String monitorDir) {
            this.service = service;
            this.copyTaskSrc = copyTaskSrc;
            this.copyTaskDst = copyTaskDst;
            this.monitorDir = monitorDir;
        }

        boolean changed(OpenlistCopyTaskPlus t) {
            return !copyTaskSrc.equals(t.getCopyTaskSrc()) || !copyTaskDst.equals(t.getCopyTaskDst()) || !monitorDir.equals(t.getMonitorDir());
        }
    }


    private final Map<Integer, MonitorInfo> monitors = new ConcurrentHashMap<>();


    /**
     * 判断配置是否修改 修改则更新监控任务
     *
     * @param tasks           the tasks
     * @param copyService     the copy service
     */
    public void reconcile(Map<Integer, OpenlistCopyTaskPlus> tasks,ICopyService copyService) {

        tasks.forEach((id, task) -> {
            MonitorInfo mi = monitors.get(id);
            if (mi == null || mi.changed(task)) {
                restart(task, copyService);
            }
        });


        monitors.keySet().removeIf(id -> {
            if (!tasks.containsKey(id)) {
                stop(id);
                return true;
            }
            return false;
        });
    }


    private void restart(OpenlistCopyTaskPlus task, ICopyService copyService) {
        stop(task.getCopyTaskId());
        start(task, copyService);
    }


    private void start(OpenlistCopyTaskPlus task, ICopyService copyService) {
        try {
            MediaUploadProcessor processor = new MediaUploadProcessor(task.getCopyTaskSrc(), task.getCopyTaskDst(), task.getMonitorDir(), copyService);

            // 监控目录通常就是下载目录：Transmission 删种挪出来的临时目录不能注册，否则会给一批
            // 正在被删除的文件提交 fs/copy，留下永远重试不成功的失败记录（与同步任务遍历同一个坑）
            OpenListHelper helper = SpringUtils.getBean(OpenListHelper.class);
            FileMonitorCoordinator svc = new FileMonitorCoordinator(
                    new WatchServiceMonitor(Paths.get(task.getMonitorDir()), helper::isTransientDir),
                    processor
            );
            svc.start();
            monitors.put(task.getCopyTaskId(), new MonitorInfo(svc, task.getCopyTaskSrc(), task.getCopyTaskDst(), task.getMonitorDir()));
            log.info("Started monitor for upload task {}", task.getCopyTaskId());
        } catch (
                Exception e) {
            log.error("Failed to start monitor for upload task {}", task.getCopyTaskId(), e);
        }
    }


    public void stop(Integer id) {
        MonitorInfo mi = monitors.remove(id);
        if (mi != null) {
            try {
                mi.service.stop();
            } catch (Exception ignored) {
            }
            log.info("Stopped monitor for upload task {}", id);
        }
    }


    public void stopAll() {
        monitors.keySet().forEach(this::stop);
    }
}