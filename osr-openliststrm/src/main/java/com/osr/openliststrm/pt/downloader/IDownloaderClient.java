package com.osr.openliststrm.pt.downloader;

import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 下载器抽象接口。新增下载器类型时实现本接口并注册为 Spring Bean，
 * {@link DownloaderClientFactory} 会自动按 {@link #type()} 分发，
 * 调用方（订阅引擎）无需改动。
 *
 * @author Jack
 */
public interface IDownloaderClient {

    /**
     * 支持的下载器类型，与 pt_downloader.type 取值一致，如 QBITTORRENT。
     */
    String type();

    /**
     * 连通性测试。任何异常均视为不连通，不向上抛。
     */
    boolean testConnection(PtDownloaderPlus config);

    /**
     * 添加种子。
     *
     * @param downloadUrl .torrent 链接或磁力链
     * @param savePath    保存路径
     * @param tag         标签，后续按此标签过滤查询
     * @throws IOException 网络异常或下载器拒绝
     */
    void addTorrent(PtDownloaderPlus config, String downloadUrl, String savePath, String tag) throws IOException;

    /**
     * 查询指定标签下的全部种子。
     *
     * @throws IOException 网络异常
     */
    List<DownloaderTorrent> listByTag(PtDownloaderPlus config, String tag) throws IOException;

    /**
     * 列出种子内的全部文件。种子刚添加、元数据尚未解析完成时应返回空列表（不抛异常），
     * 调用方据此判断需要下一轮轮询重试。
     *
     * @throws IOException 网络异常
     */
    List<DownloaderTorrentFile> listFiles(PtDownloaderPlus config, String hash) throws IOException;

    /**
     * 将指定文件从下载队列中排除（不下载），用于季包/区间匹配时只保留目标集数对应的文件。
     *
     * @param fileIndexes 要排除的文件序号，对应 {@link DownloaderTorrentFile#getIndex()}
     * @throws IOException 网络异常
     */
    void excludeFiles(PtDownloaderPlus config, String hash, Set<Integer> fileIndexes) throws IOException;

    /**
     * 给指定种子设置分享限额，用于 H&R 保种防护。
     * <p>
     * 下载器的自动管理/全局做种限额会在达到限额后自动停止甚至删除种子。若全局限额比站点的
     * H&R 要求宽松，种子会在考核达标前就被下载器自己清掉——这是 OSR 无法事后补救的一类
     * H&R，因此把站点要求下发成种子级限额是唯一的主动防线。
     * </p>
     *
     * @param ratioLimit          分享率上限，&lt;=0 表示该维度不限
     * @param seedingTimeMinutes  做种时长上限（分钟），&lt;=0 表示该维度不限
     * @throws IOException 网络异常或下载器拒绝
     */
    void setShareLimits(PtDownloaderPlus config, String hash, double ratioLimit, long seedingTimeMinutes)
            throws IOException;
}
