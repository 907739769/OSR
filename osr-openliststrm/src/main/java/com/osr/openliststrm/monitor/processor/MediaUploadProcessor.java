package com.osr.openliststrm.monitor.processor;

import com.osr.common.utils.LogOnce;
import com.osr.openliststrm.service.ICopyService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author: Jack
 * @creat: 2026/1/13 21:13
 */
@Slf4j
public class MediaUploadProcessor implements FileProcessor {

    private final String copyTaskSrc;
    private final String copyTaskDst;
    private final String monitorDir;
    private final ICopyService copyService;

    private final Set<Path> processing = ConcurrentHashMap.newKeySet();

    /**
     * 「文件仍在写入」按路径去重。这是<b>持续状态</b>不是事件：文件每被写一次就来一个
     * ENTRY_MODIFY，同一个路径每轮都得到同一个答案。实测一个临时文件在 3 秒内刷了 569 行。
     * 文件转为稳定时 forget，之后它再次被改写还会照常记一次。
     */
    private final LogOnce writingLogged = new LogOnce();

    public MediaUploadProcessor(String copyTaskSrc, String copyTaskDst, String monitorDir, ICopyService copyService) {
        this.copyTaskSrc = copyTaskSrc;
        this.copyTaskDst = copyTaskDst;
        this.monitorDir = monitorDir;
        this.copyService = copyService;
    }

    @Override
    public void process(Path file) {
        Path p = file.toAbsolutePath().normalize();
        String fileName = p.toString();
        // 1. 忽略下载器/网盘客户端的在途产物（清单与判据见 FileStabilityUtils）
        if (FileStabilityUtils.isTransientArtifact(p)) {
            return;
        }
        //判断文件是否还在写入中
        if (!FileStabilityUtils.isFileStable(p)) {
            if (writingLogged.firstTime(fileName)) {
                log.debug("文件仍在写入，稍后再试：{}", p);
            }
            return;
        }
        writingLogged.forget(fileName);
        if (!processing.add(p)) {
            log.debug("跳过重复处理：{}", p);
            return;
        }
        try {
            copyService.syncOneFile(copyTaskSrc, copyTaskDst, file.toAbsolutePath().toString().replace(monitorDir, ""));
        } catch (Exception e) {
            log.error("处理文件失败：{}", p, e);
        } finally {
            processing.remove(p);
        }
    }


}
