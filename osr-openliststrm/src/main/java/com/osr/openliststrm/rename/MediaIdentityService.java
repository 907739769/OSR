package com.osr.openliststrm.rename;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.mybatisplus.domain.MediaIdentityBindingPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.mapper.MediaIdentityBindingPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import org.springframework.stereotype.Service;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;

import java.io.IOException;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.orphan.StrmSourcePathResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 只使用已确认的下载归属和人工选择；不按片名猜测文件归属。 */
@Service
public class MediaIdentityService {
    private final MediaIdentityBindingPlusMapper mapper;
    private final IOpenlistStrmPlusService strmService;
    private final DownloaderClientFactory clients;
    private final OpenListHelper helper;
    private final OpenlistConfig config;
    private final MediaIdentityBindingStore store;
    private final IPtDownloadRecordPlusService downloads;
    private final IPtDownloaderPlusService downloaders;
    private final IPtSubscriptionPlusService subscriptions;
    private final Object[] downloadLocks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final MediaParser localParser = new MediaParser(null, null);

    public MediaIdentityService(MediaIdentityBindingPlusMapper mapper, IOpenlistStrmPlusService strmService,
                                DownloaderClientFactory clients, OpenListHelper helper, OpenlistConfig config,
                                MediaIdentityBindingStore store, IPtDownloadRecordPlusService downloads,
                                IPtDownloaderPlusService downloaders, IPtSubscriptionPlusService subscriptions) {
        this.mapper = mapper;
        this.strmService = strmService;
        this.clients = clients;
        this.helper = helper;
        this.config = config;
        this.store = store;
        this.downloads = downloads;
        this.downloaders = downloaders;
        this.subscriptions = subscriptions;
    }

    /** 快照按推送批次校验，复用的下载记录不能复用上一次的身份和文件选择。 */
    public void captureDownload(PtDownloadRecordPlus record, PtDownloaderPlus downloader,
                                DownloaderTorrent torrent, PtSubscriptionPlus sub) throws Exception {
        if (sub == null || sub.getTmdbId() == null) return;
        synchronized (downloadLock(record.getId())) {
            if (snapshotReady(record)) return;
            saveDownloadFiles(record, torrent, sub, clients.get(downloader).listFiles(downloader, torrent.getHash()));
        }
    }

    /** 文件选择发生变化时总是完整替换；删除旧清单，避免只要有一条绑定就永远跳过更新。 */
    public void captureDownloadFiles(PtDownloadRecordPlus record, DownloaderTorrent torrent,
                                     PtSubscriptionPlus sub, List<DownloaderTorrentFile> files) throws IOException {
        if (sub == null || sub.getTmdbId() == null) return;
        synchronized (downloadLock(record.getId())) {
            saveDownloadFiles(record, torrent, sub, files);
        }
    }

    private void saveDownloadFiles(PtDownloadRecordPlus record, DownloaderTorrent torrent,
                                   PtSubscriptionPlus sub, List<DownloaderTorrentFile> files) throws IOException {
        MediaIdentity identity = new MediaIdentity(sub.getTmdbId(), sub.getMediaType().toLowerCase(Locale.ROOT),
                sub.getSeason() == null ? null : sub.getSeason().toString(), null);
        if (files == null || files.isEmpty()) throw new IOException("尚未取得下载文件列表");
        String savePath = torrent.getSavePath();
        if (savePath == null || savePath.isBlank()) throw new IOException("下载器未返回文件保存路径");
        String version = downloadVersion(record);
        List<MediaIdentityBindingPlus> bindings = new ArrayList<>();
        for (DownloaderTorrentFile file : files) {
            if (!file.isWanted() || file.getName() == null || !helper.isVideo(file.getName()) || file.getSize() <= 0) continue;
            String relative = normalizedRelative(file.getName());
            if (relative == null) continue;
            // 动画的文件季号可能采用 TMDb 口径，订阅季号采用本地口径，两者不能互相覆盖。
            // 多季包同理：身份确定到作品，季集号仍按具体文件抽取；没写季号时再用订阅补足。
            String season = localParser.parseLocal(file.getName()).getSeason();
            MediaIdentityBindingPlus binding = binding(new MediaIdentity(identity.tmdbId(), identity.mediaType(),
                    season == null ? identity.season() : season, null));
            binding.setBindingKey(hash("download:" + record.getId() + ":" + relative));
            binding.setSourceKind("DOWNLOAD");
            binding.setDownloadId(record.getId());
            binding.setDownloadVersion(version);
            binding.setRelativePath(relative);
            binding.setSourcePath(normalize(savePath) + "/" + relative);
            binding.setFileNameHash(hash(fileName(relative)));
            binding.setFileSize(file.getSize());
            bindings.add(binding);
        }
        MediaIdentityBindingPlus snapshot = binding(identity);
        snapshot.setBindingKey(snapshotKey(record.getId()));
        snapshot.setSourceKind("SNAPSHOT");
        snapshot.setDownloadId(record.getId());
        snapshot.setDownloadVersion(version);
        snapshot.setSourcePath(normalize(savePath));
        snapshot.setFileNameHash(hash("snapshot"));
        store.replaceDownload(record.getId(), bindings, snapshot);
    }

    /** 人工选择先落库，TMDb 临时不可用时重试仍用同一身份。 */
    public void pin(Path source, MediaIdentity identity) {
        MediaIdentityBindingPlus binding = binding(identity);
        String path = source.toAbsolutePath().normalize().toString();
        binding.setBindingKey(hash("manual:" + path));
        binding.setSourceKind("MANUAL");
        binding.setSourcePath(path);
        binding.setFileNameHash(hash(source.getFileName().toString()));
        mapper.upsert(binding);
    }

    /** 无可靠关联返回 null；存在互相矛盾的关联时抛错，调用方必须停止自动处理。 */
    public MediaIdentity resolve(Path source) throws IOException {
        Path path = source.toAbsolutePath().normalize();
        MediaIdentityBindingPlus manual = mapper.selectById(hash("manual:" + path));
        if (manual != null) return identity(manual);

        Origin origin = origin(path);
        if (origin == null) return null;
        // 记录在加种前就已保存。首次轮询前的快下载也必须先核对实际清单，不能直接猜片。
        Map<Integer, PtDownloadRecordPlus> active = new HashMap<>();
        MediaIdentityPendingException pending = null;
        List<PtDownloadRecordPlus> inFlight = downloads.list(new QueryWrapper<PtDownloadRecordPlus>()
                .in("state", "PUSHED", "DOWNLOADING"));
        Map<Integer, String> versions = new HashMap<>();
        if (!inFlight.isEmpty()) {
            for (MediaIdentityBindingPlus snapshot : mapper.selectList(new QueryWrapper<MediaIdentityBindingPlus>()
                    .eq("source_kind", "SNAPSHOT")
                    .in("download_id", inFlight.stream().map(PtDownloadRecordPlus::getId).toList()))) {
                versions.put(snapshot.getDownloadId(), snapshot.getDownloadVersion());
            }
        }
        for (PtDownloadRecordPlus record : inFlight) {
            active.put(record.getId(), record);
            if (Objects.equals(versions.get(record.getId()), downloadVersion(record))) continue;
            try { refreshPending(record, origin); }
            catch (MediaIdentityPendingException e) { pending = e; }
        }
        List<MediaIdentityBindingPlus> candidates = mapper.selectList(new QueryWrapper<MediaIdentityBindingPlus>()
                .eq("source_kind", "DOWNLOAD").eq("file_name_hash", hash(fileName(origin.path()))));
        Set<MediaIdentity> matches = new HashSet<>();
        boolean sizeMismatch = false;
        for (MediaIdentityBindingPlus candidate : candidates) {
            String relative = candidate.getRelativePath();
            if (relative == null || !(origin.path().equals(normalize(candidate.getSourcePath()))
                    || origin.path().endsWith("/" + relative))) continue;
            PtDownloadRecordPlus record = active.containsKey(candidate.getDownloadId())
                    ? active.get(candidate.getDownloadId()) : downloads.getById(candidate.getDownloadId());
            if (record != null && !Objects.equals(candidate.getDownloadVersion(), downloadVersion(record))) {
                // 不信任复用记录留下的旧清单；完整刷新后由下一次重试重新读取。
                refreshPending(record, origin);
                throw new MediaIdentityPendingException("下载记录已重新推送，正在刷新作品绑定");
            }
            // 没拿到源视频大小的 STRM 也可能已匹配到已知下载，不能再回退为标题搜索。
            if (origin.size() == null) throw new MediaIdentityPendingException("STRM 生成记录尚未就绪，等待原视频大小落库");
            if (!origin.size().equals(candidate.getFileSize())) {
                sizeMismatch = true;
                continue;
            }
            matches.add(identity(candidate));
        }
        if (matches.size() > 1) throw new IllegalStateException("该文件关联到多个作品或季，请指定正确的 TMDb ID 和季号");
        if (matches.isEmpty() && sizeMismatch) {
            throw new MediaIdentityPendingException("文件大小与下载记录不一致，等待下载或同步完成");
        }
        if (matches.isEmpty() && pending != null) throw pending;
        return matches.stream().findFirst().orElse(null);
    }

    /** 不靠种子标题推断文件归属，只查询记录的唯一跟踪标签和下载器实际文件清单。 */
    private void refreshPending(PtDownloadRecordPlus record, Origin origin) {
        synchronized (downloadLock(record.getId())) {
            if (snapshotReady(record)) return;
            try {
                PtDownloaderPlus downloader = downloaders.getById(record.getDownloaderId());
                PtSubscriptionPlus sub = subscriptions.getById(record.getSubId());
                if (downloader == null || sub == null || record.getTrackingTag() == null) {
                    throw new MediaIdentityPendingException("下载记录的作品或下载器配置尚未就绪");
                }
                List<DownloaderTorrent> torrents = clients.get(downloader).listByTag(downloader, record.getTrackingTag());
                if (torrents.size() != 1) throw new MediaIdentityPendingException("等待下载器返回唯一的下载任务");
                DownloaderTorrent torrent = torrents.getFirst();
                List<DownloaderTorrentFile> files = clients.get(downloader).listFiles(downloader, torrent.getHash());
                if (files == null || files.isEmpty()) throw new MediaIdentityPendingException("等待下载器解析文件清单");
                if (!torrent.isCompleted() && !Boolean.TRUE.equals(record.getFilesSelected())) {
                    // 元数据已足以排除无关文件；目标资源等文件选择完成后再使用订阅身份。
                    boolean relevant = files.stream().map(DownloaderTorrentFile::getName)
                            .filter(Objects::nonNull).map(MediaIdentityService::normalizedRelative)
                            .filter(Objects::nonNull).anyMatch(relative -> origin.path().endsWith("/" + relative));
                    if (relevant) throw new MediaIdentityPendingException("等待下载器完成文件选择");
                    return;
                }
                saveDownloadFiles(record, torrent, sub, files);
            } catch (MediaIdentityPendingException e) { throw e; }
            catch (Exception e) { throw new MediaIdentityPendingException("下载作品绑定尚未就绪，稍后重试", e); }
        }
    }

    private boolean snapshotReady(PtDownloadRecordPlus record) {
        MediaIdentityBindingPlus snapshot = mapper.selectById(snapshotKey(record.getId()));
        return snapshot != null && Objects.equals(snapshot.getDownloadVersion(), downloadVersion(record));
    }

    private Object downloadLock(Integer id) { return downloadLocks[Math.floorMod(id, downloadLocks.length)]; }
    private static String snapshotKey(Integer id) { return hash("snapshot:" + id); }
    private static String downloadVersion(PtDownloadRecordPlus record) {
        return hash(record.getSubId() + ":" + record.getDownloaderId() + ":" + record.getTrackingTag() + ":"
                + record.getEpisode() + ":" + record.getEpisodeEnd() + ":" + record.getGuidHash() + ":"
                + (record.getPushedTime() == null ? "" : record.getPushedTime().getTime() / 1000));
    }

    private Origin origin(Path source) throws IOException {
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".strm")) {
            return new Origin(normalize(source.toString()), Files.size(source));
        }
        // 只读本地 STRM 内容，不访问其 URL；使用 /d 后的原视频路径和生成记录中的视频大小。
        if (Files.size(source) > 65536) return null;
        String remote = StrmSourcePathResolver.resolve(Files.readString(source).strip(),
                config.getOpenListUrl(), "1".equals(config.getOpenListStrmEncode()));
        if (remote == null || !remote.startsWith("/")) return null;
        remote = normalize(remote);
        int slash = remote.lastIndexOf('/');
        List<OpenlistStrmPlus> records = strmService.list(new QueryWrapper<OpenlistStrmPlus>()
                .eq("strm_path", remote.substring(0, slash)).eq("strm_file_name", remote.substring(slash + 1)));
        Set<Long> sizes = new HashSet<>();
        for (OpenlistStrmPlus record : records) if (record.getFileSize() != null) sizes.add(record.getFileSize());
        return new Origin(remote, sizes.size() == 1 ? sizes.iterator().next() : null);
    }

    private static MediaIdentityBindingPlus binding(MediaIdentity identity) {
        MediaIdentityBindingPlus binding = new MediaIdentityBindingPlus();
        binding.setTmdbId(identity.tmdbId());
        binding.setMediaType(identity.mediaType());
        binding.setSeason(identity.season());
        binding.setEpisode(identity.episode());
        return binding;
    }

    private static MediaIdentity identity(MediaIdentityBindingPlus binding) {
        return new MediaIdentity(binding.getTmdbId(), binding.getMediaType(), binding.getSeason(), binding.getEpisode());
    }

    private static String normalizedRelative(String raw) {
        String relative = raw.replace('\\', '/');
        if (relative.startsWith("/") || relative.matches("^[A-Za-z]:.*")) return null;
        for (String part : relative.split("/")) if (part.isBlank() || part.equals(".") || part.equals("..")) return null;
        return relative;
    }

    private static String normalize(String path) { return path.replace('\\', '/').replaceAll("/+$", ""); }
    private static String fileName(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record Origin(String path, Long size) {}
}
