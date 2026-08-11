package com.osr.openliststrm.rename;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.monitor.service.FileMonitorCoordinator;
import com.osr.openliststrm.monitor.WatchServiceMonitor;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.monitor.processor.MediaRenameProcessor;
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
public class RenameMonitorRegistry {

    private static class MonitorInfo {
        final FileMonitorCoordinator service;
        final String source;
        final String target;
        final String scrapeEnabled;
        final String scrapeNfo;
        final String scrapeImages;

        MonitorInfo(FileMonitorCoordinator service, String source, String target,
                    String scrapeEnabled, String scrapeNfo, String scrapeImages) {
            this.service = service;
            this.source = source;
            this.target = target;
            this.scrapeEnabled = scrapeEnabled;
            this.scrapeNfo = scrapeNfo;
            this.scrapeImages = scrapeImages;
        }

        boolean changed(RenameTaskPlus t) {
            String src = t.getSourceFolder();
            String tgt = t.getTargetRoot();
            String sEnabled = t.getScrapeEnabled() != null ? t.getScrapeEnabled() : "0";
            String sNfo = t.getScrapeNfo() != null ? t.getScrapeNfo() : "0";
            String sImages = t.getScrapeImages() != null ? t.getScrapeImages() : "0";
            return !source.equals(src)
                    || !target.equals(tgt)
                    || !scrapeEnabled.equals(sEnabled)
                    || !scrapeNfo.equals(sNfo)
                    || !scrapeImages.equals(sImages);
        }
    }


    private final Map<Integer, MonitorInfo> monitors = new ConcurrentHashMap<>();


    /**
     * 判断配置是否修改 修改则更新监控任务
     *
     * @param tasks           the tasks
     * @param clientProvider  the client provider
     * @param helper          the helper
     * @param config          the config
     * @param listenerFactory the listener factory
     */
    public void reconcile(Map<Integer, RenameTaskPlus> tasks,
                          RenameClientProvider clientProvider,
                          OpenListHelper helper,
                          OpenlistConfig config,
                          RenameEventListenerFactory listenerFactory) {

        tasks.forEach((id, task) -> {
            MonitorInfo mi = monitors.get(id);
            if (mi == null || mi.changed(task)) {
                restart(task, clientProvider, helper, config, listenerFactory);
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


    private void restart(RenameTaskPlus task,
                         RenameClientProvider clientProvider,
                         OpenListHelper helper,
                         OpenlistConfig config,
                         RenameEventListenerFactory listenerFactory) {
        stop(task.getId());
        start(task, clientProvider, helper, config, listenerFactory);
    }


    private void start(RenameTaskPlus task,
                       RenameClientProvider clientProvider,
                       OpenListHelper helper,
                       OpenlistConfig config,
                       RenameEventListenerFactory listenerFactory) {
        try {
            MediaRenameProcessor processor = new MediaRenameProcessor(
                    Paths.get(task.getTargetRoot()),
                    clientProvider,
                    helper,
                    config,
                    listenerFactory.create(task.getId())
            );


            // 源目录通常就是下载目录：Transmission 删种时挪出来的临时目录不能注册，
            // 否则会对着一批正在被删除的文件跑重命名，见 OpenListHelper#isTransientDir
            FileMonitorCoordinator svc = new FileMonitorCoordinator(
                    new WatchServiceMonitor(Paths.get(task.getSourceFolder()), helper::isTransientDir),
                    processor
            );
            svc.start();
            monitors.put(task.getId(), new MonitorInfo(
                    svc,
                    task.getSourceFolder(),
                    task.getTargetRoot(),
                    task.getScrapeEnabled() != null ? task.getScrapeEnabled() : "0",
                    task.getScrapeNfo() != null ? task.getScrapeNfo() : "0",
                    task.getScrapeImages() != null ? task.getScrapeImages() : "0"
            ));
            log.info("Started monitor for rename task {}", task.getId());
        } catch (
                Exception e) {
            log.error("Failed to start monitor for rename task {}", task.getId(), e);
        }
    }


    public void stop(Integer id) {
        MonitorInfo mi = monitors.remove(id);
        if (mi != null) {
            try {
                mi.service.stop();
            } catch (Exception ignored) {
            }
            log.info("Stopped monitor for rename task {}", id);
        }
    }


    public void stopAll() {
        monitors.keySet().forEach(this::stop);
    }
}