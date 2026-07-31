package com.osr.openliststrm.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyTaskPlusService;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmTaskPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService;
import com.osr.openliststrm.rename.RenameTaskManager;
import com.osr.openliststrm.service.ICopyService;
import com.osr.openliststrm.service.IStrmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @Author Jack
 * @Date 2025/7/17 18:46
 * @Version 1.0.0
 */
@Slf4j
@Component("openListStrmTask")
public class OpenListStrmTask {

    @Autowired
    private IOpenlistCopyTaskPlusService copyTaskPlusService;

    @Autowired
    private IOpenlistStrmTaskPlusService strmTaskPlusService;

    @Autowired
    private ICopyService copyService;

    @Autowired
    private IStrmService strmService;

    @Autowired
    private RenameTaskManager renameTaskManager;

    @Autowired
    private IRenameTaskPlusService renameTaskPlusService;

    @Autowired
    private com.osr.openliststrm.orphan.IRenameOrphanScanService renameOrphanScanService;

    public void copy() {
        LambdaQueryWrapper<OpenlistCopyTaskPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OpenlistCopyTaskPlus::getCopyTaskStatus, "1");
        List<OpenlistCopyTaskPlus> taskList = copyTaskPlusService.list(wrapper);
        taskList.forEach(task -> {
            Date lastSyncTime = task.getLastSyncTime();
            if (lastSyncTime != null) {
                // 增量同步
                log.info("执行增量复制任务 ID={}, 基准时间={}", task.getCopyTaskId(), lastSyncTime);
                copyService.syncFilesIncremental(task.getCopyTaskSrc(), task.getCopyTaskDst(), lastSyncTime);
            } else {
                // 首次全量同步
                log.info("执行全量复制任务 ID={}", task.getCopyTaskId());
                copyService.syncFiles(task.getCopyTaskSrc(), task.getCopyTaskDst());
            }
            // 更新同步时间
            task.setLastSyncTime(new Date());
            copyTaskPlusService.updateById(task);
        });
    }

    /**
     * 强制全量同步（忽略 last_sync_time）
     */
    public void copyFull() {
        LambdaQueryWrapper<OpenlistCopyTaskPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OpenlistCopyTaskPlus::getCopyTaskStatus, "1");
        List<OpenlistCopyTaskPlus> taskList = copyTaskPlusService.list(wrapper);
        taskList.forEach(task -> {
            log.info("执行全量复制任务 ID={}", task.getCopyTaskId());
            copyService.syncFiles(task.getCopyTaskSrc(), task.getCopyTaskDst());
            task.setLastSyncTime(new Date());
            copyTaskPlusService.updateById(task);
        });
    }

    public void strm() {
        LambdaQueryWrapper<OpenlistStrmTaskPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OpenlistStrmTaskPlus::getStrmTaskStatus, "1");
        List<OpenlistStrmTaskPlus> taskList = strmTaskPlusService.list(wrapper);
        taskList.forEach(task -> {
            strmService.strmDir(task.getStrmTaskPath());
        });
    }

    public void rename() {
        LambdaQueryWrapper<RenameTaskPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RenameTaskPlus::getStatus, "1");
        List<RenameTaskPlus> taskList = renameTaskPlusService.list(wrapper);
        taskList.forEach(task -> {
            renameTaskManager.executeTaskNow(task.getId());
        });
    }

    public void checkRenameOrphan() {
        renameOrphanScanService.scan();
    }

}
