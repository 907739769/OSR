package com.osr.openliststrm.pt.model;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 受保护种子名单：把一批下载记录展开成三种可比对的身份，用来回答"下载器里这个种子
 * 是不是对应着一条不能动的记录"。
 * <p>
 * 三种身份都要，因为它们各有失效的时候：{@code torrent_hash} 来自索引器的 infohash 属性、
 * 很多站不给；{@code tracking_tag} 在种子被转移到另一个下载器后未必被保留；种子名则可能
 * 被用户改过。任一命中即保护——宁可多保护（种子多留几天）也不能漏保护。
 * </p>
 * <p>
 * 自动删种（{@code TorrentCleanService}）与转移做种（{@code TorrentTransferService}）
 * 都要判"这个种子能不能动"，共用<b>同一份</b>匹配实现：三路降级匹配是这里最容易写错的部分，
 * 两处各写一遍必然漂移，而漂移的表现是"某一侧偶尔漏保护"，几乎无法从日志追出来。
 * 至于"该保护哪些记录"（H&R 考核中、还有集在途……）由各业务自己查，那是各自的判断。
 * </p>
 *
 * @author Jack
 */
public final class ProtectedTorrents {

    private final Set<String> hashes;
    private final Set<String> tags;
    private final Set<String> names;

    private ProtectedTorrents(Set<String> hashes, Set<String> tags, Set<String> names) {
        this.hashes = hashes;
        this.tags = tags;
        this.names = names;
    }

    public static ProtectedTorrents of(List<PtDownloadRecordPlus> records) {
        Set<String> hashes = new HashSet<>();
        Set<String> tags = new HashSet<>();
        Set<String> names = new HashSet<>();
        if (records != null) {
            for (PtDownloadRecordPlus record : records) {
                addIfPresent(hashes, record.getTorrentHash());
                addIfPresent(tags, record.getTrackingTag());
                addIfPresent(names, record.getTitle());
            }
        }
        return new ProtectedTorrents(hashes, tags, names);
    }

    private static void addIfPresent(Set<String> target, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.add(value.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** 名单里是否有任何一条记录指向这个种子 */
    public boolean covers(DownloaderTorrent torrent) {
        if (StringUtils.isNotBlank(torrent.getHash())
                && hashes.contains(torrent.getHash().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (StringUtils.isNotBlank(torrent.getName())
                && names.contains(torrent.getName().trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (StringUtils.isBlank(torrent.getTags()) || tags.isEmpty()) {
            return false;
        }
        for (String tag : torrent.getTags().split(",")) {
            if (tags.contains(tag.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
