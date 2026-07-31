package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;

/**
 * <p>
 * PT 下载器配置 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
public interface IPtDownloaderPlusService extends IService<PtDownloaderPlus> {

    /**
     * 校验保存路径是否位于某个已启用文件同步任务的监听目录之下。
     * 不满足时下载完成的文件不会被现有 FileMonitor 链路接管。
     *
     * @return 校验失败的提示信息；通过时返回 null
     */
    String validateSavePath(String savePath);
}
