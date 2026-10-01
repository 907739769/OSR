package com.osr.openliststrm.pt.media;

/**
 * 至少一台媒体服务器刚收下了一批刷新通知（{@link LibraryRefreshNotifier} 发出）。
 * <p>
 * 用事件而不是让通知器直接调对账任务：对账链路往下会走到 STRM 生成、刮削这些本身就依赖通知器的类，
 * 直接注入是一个依赖环。监听方见 {@code LibrarySyncTask#onLibraryRefreshed}。
 * </p>
 *
 * @param targets 本批通知的目录总数（各服务器相加），只用于日志
 */
public record LibraryRefreshedEvent(int targets) {
}
