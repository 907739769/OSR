package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;

import java.util.List;

/**
 * 一轮轮询里某个下载器的种子快照。
 * <p>
 * 存在的理由只有一个：H&R 追踪必须能跨下载器看。用户用 IYUU 把下载完成的种子
 * <b>转移</b>到保种机上之后，种子会从原下载器彻底消失，而
 * {@code DownloadTrackService#trackSeeding} 原本把"在本下载器里找不到"直接判成
 * "H&R 已产生"——每一条被转移的记录都会收到一条假告警。把整轮所有下载器的快照
 * 一起交给追踪逻辑，它才能先去别处找一遍再下结论。
 * </p>
 *
 * @param downloader 下载器配置
 * @param torrents   本轮从该下载器拉到的种子。仅做种的下载器拉的是<b>全量</b>
 *                   （IYUU 加进来的种子不带 OSR 标签，按标签查一条都看不见）
 * @author Jack
 */
public record DownloaderSnapshot(PtDownloaderPlus downloader, List<DownloaderTorrent> torrents) {
}
