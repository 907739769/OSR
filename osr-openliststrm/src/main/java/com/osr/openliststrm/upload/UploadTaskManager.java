package com.osr.openliststrm.upload;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyTaskPlusService;
import com.osr.openliststrm.service.ICopyService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author: Jack
 * @creat: 2026/1/14 14:10
 */
@Slf4j
@Component
public class UploadTaskManager {

    @Autowired
    private IOpenlistCopyTaskPlusService copyTaskPlusService;
    @Autowired
    private ICopyService copyService;

    private final UploadMonitorRegistry registry = new UploadMonitorRegistry();
    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(10), Duration.ofSeconds(10));
        log.info("UploadTaskManager started");
    }

    @PreDestroy
    public void stop() {
        registry.stopAll();
        log.info("UploadTaskManager stopped");
        MDC.clear();
    }

    private void poll() {
        try {
            //查询所有启用的文件同步任务
            Map<Integer, OpenlistCopyTaskPlus> activeTasks = loadActiveTasks();
            registry.reconcile(activeTasks, copyService);
        } catch (Exception e) {
            log.error("poll error", e);
        }
    }


    private Map<Integer, OpenlistCopyTaskPlus> loadActiveTasks() {
        QueryWrapper<OpenlistCopyTaskPlus> qw = new QueryWrapper<>();
        qw.eq("copy_task_status", "1");
        qw.isNotNull("monitor_dir");
        qw.ne("monitor_dir", "");
        List<OpenlistCopyTaskPlus> list = copyTaskPlusService.list(qw);
        return list.stream().collect(Collectors.toMap(OpenlistCopyTaskPlus::getCopyTaskId, t -> t));
    }
}
