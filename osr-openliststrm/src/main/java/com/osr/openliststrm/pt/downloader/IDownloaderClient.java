package com.osr.openliststrm.pt.downloader;

import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.pt.downloader.model.AddTorrentOutcome;
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
     * @param paused      是否以暂停态添加，等 OSR 按目标集选完文件后再
     *                    {@link #resumeTorrent} 启动。<b>磁力链绝不能传 true</b>：
     *                    下载器在暂停态下不会去下载磁力的元数据，文件列表永远为空，
     *                    种子会永远等不到启动
     * @throws IOException 网络异常或下载器拒绝
     */
    void addTorrent(PtDownloaderPlus config, String downloadUrl, String savePath, String tag, boolean paused)
            throws IOException;

    /**
     * 启动一个暂停中的种子。
     * <p>
     * 对已经在下载的种子调用是幂等的无害操作——调用方因此不需要记录"这个种子当初是不是
     * 暂停加进来的"，选完文件无脑启动即可，省掉一列状态和它带来的一致性问题。
     * </p>
     *
     * @throws IOException 网络异常或下载器拒绝
     */
    void resumeTorrent(PtDownloaderPlus config, String hash) throws IOException;

    /**
     * 从下载器移除种子。
     * <p>
     * <b>调用它之前先读根目录 AGENTS.md 的「OSR 从不删种」。</b>只有两个受控例外：
     * <ul>
     *   <li>{@code DownloadTrackService#removeUselessTorrent}：种子从未下载完成、也从未做种，
     *       站点的 H&R 考核根本没开始计，删它不可能记过。</li>
     *   <li>{@code TorrentCleanService}：用户为某个下载器<b>显式开启</b>了自动删种并配了规则，
     *       且该种子已过 H&R 考核、辅种组内所有兄弟种子同时达标。</li>
     * </ul>
     * 任何其它场景都不要调用本方法。
     * </p>
     *
     * @param deleteFiles 是否连同已下载的文件一起删除
     * @throws IOException 网络异常或下载器拒绝
     */
    void deleteTorrent(PtDownloaderPlus config, String hash, boolean deleteFiles) throws IOException;

    /**
     * 查询指定标签下的全部种子。
     *
     * @throws IOException 网络异常
     */
    List<DownloaderTorrent> listByTag(PtDownloaderPlus config, String tag) throws IOException;

    /**
     * 查询下载器里的<b>全部</b>种子，不按标签过滤。
     * <p>
     * 自动删种与「IYUU 转移后继续追踪 H&R」都必须用它：IYUU 转移/辅种加进来的种子不带
     * OSR 的标签，{@link #listByTag} 一条都看不见，据此判断"种子不见了"会得出完全错误的结论。
     * </p>
     *
     * @throws IOException 网络异常
     */
    List<DownloaderTorrent> listAll(PtDownloaderPlus config) throws IOException;

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

    /**
     * 按 hash 查单个种子，下载器里没有则返回 {@code null}。
     * <p>
     * 与 {@link #listAll} 的分工是「问一个种子」和「问全部种子」：转移做种要在每一轮里
     * 反复确认某个种子的校验进度，用全量查询等于每次把整台机器的种子列表拉一遍。
     * </p>
     *
     * @throws IOException 网络异常
     */
    DownloaderTorrent getTorrent(PtDownloaderPlus config, String hash) throws IOException;

    /**
     * 本下载器能不能导出 .torrent 本体，也就是能不能作为转移做种的<b>来源</b>。
     * <p>
     * 做成能力声明而不是让调用方按类型硬判断，理由与 {@code INotifier#supportsDirectDelivery()}
     * 相同：调用方问的是能不能做这件事，不该去记住哪些实现类支持。转移侧据此在规则一开始
     * 就报错，而不是让每个种子各失败一次、刷出一屏一模一样的记录。
     * </p>
     */
    default boolean supportsExport() {
        return true;
    }

    /**
     * 导出种子的 .torrent 原始字节，用于把种子转移到另一个下载器继续做种。
     * <p>
     * 必须导出种子文件本体而不是用磁力链重加：磁力链要重新向 DHT/tracker 拉一次元数据，
     * 私有站点的种子多半禁用 DHT，元数据永远拉不回来，种子就卡在那里。
     * </p>
     *
     * @return .torrent 文件的完整字节；下载器不支持导出或种子不存在时抛异常，不返回空数组
     * @throws IOException 网络异常、下载器版本不支持导出端点、或种子不存在
     */
    byte[] exportTorrent(PtDownloaderPlus config, String hash) throws IOException;

    /**
     * 用 .torrent 文件字节流添加种子。
     * <p>
     * 转移做种专用：数据已经在盘上，加进来只是为了让另一个下载器接手做种。调用方应当传
     * {@code paused=true}，随后调用 {@link #recheckTorrent} 校验本地数据，确认进度到 100%
     * 之后再 {@link #resumeTorrent}——直接以运行态加入的话，一旦保存路径对不上，
     * 下载器会立刻把整个种子重新下载一遍。
     * </p>
     *
     * @param metainfo .torrent 文件的完整字节
     * @param savePath 目标下载器视角下的保存路径（两台机器挂载点不同时，调用方负责先做映射）
     * @throws IOException 网络异常或下载器拒绝
     */
    AddTorrentOutcome addTorrentFile(PtDownloaderPlus config, byte[] metainfo, String savePath,
                                     String tag, boolean paused) throws IOException;

    /**
     * 触发种子的本地数据校验（qBittorrent 的 recheck / Transmission 的 verify）。
     * <p>
     * 校验是<b>异步</b>的：本方法返回只代表下载器接受了请求，进度要靠
     * {@link #getTorrent} 轮询 {@code progress} 与 {@code checking} 得到。
     * </p>
     *
     * @throws IOException 网络异常或下载器拒绝
     */
    void recheckTorrent(PtDownloaderPlus config, String hash) throws IOException;
}
