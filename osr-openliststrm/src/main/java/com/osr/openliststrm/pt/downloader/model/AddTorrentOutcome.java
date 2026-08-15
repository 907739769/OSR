package com.osr.openliststrm.pt.downloader.model;

/**
 * 用 .torrent 文件加种的结果。
 * <p>
 * 转移做种必须能区分这两种结果，因为它们的<b>回滚语义完全相反</b>：校验不通过时，
 * {@link #ADDED} 的种子是本次转移刚加进去的、撤掉它天经地义；而 {@link #DUPLICATE}
 * 说明目标下载器里本来就有这个种子（用户自己加的、或上一轮转移留下的），
 * 撤掉它等于替用户删了一个正在做种的任务。
 * </p>
 *
 * @author Jack
 */
public enum AddTorrentOutcome {

    /** 种子是本次调用新加进去的 */
    ADDED,

    /**
     * 目标下载器里已存在同 infohash 的种子，本次调用没有产生新任务。
     * <p>
     * 只有 Transmission 能明确给出这个结论（{@code torrent-duplicate}）。qBittorrent 对
     * 重复种子同样返回 {@code Ok.}，无从分辨，一律报 {@link #ADDED}——所以「转移前先查
     * 目标端有没有同 hash 的种子」是主防线，本枚举只是第二道。
     * </p>
     */
    DUPLICATE
}
