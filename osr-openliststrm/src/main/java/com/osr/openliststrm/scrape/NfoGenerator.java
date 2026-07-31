package com.osr.openliststrm.scrape;

import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 生成 Emby/Jellyfin/Plex 兼容的 NFO 文件。
 * 利用 MediaInfo.metadata 中已有的 TMDb 详情 JsonNode。
 * <p>
 * 门面类：根据媒体类型选择对应的策略实现（{@link NfoTypeStrategy}），
 * 委托具体的 Builder 生成 NFO 内容。
 */
@Slf4j
@Component
public class NfoGenerator {

    private final MovieNfoBuilder movieBuilder = new MovieNfoBuilder();
    private final TvShowNfoBuilder tvShowBuilder = new TvShowNfoBuilder();
    private final SeasonNfoBuilder seasonBuilder = new SeasonNfoBuilder();
    private final EpisodeNfoBuilder episodeBuilder = new EpisodeNfoBuilder();

    // ==================== Public API ====================

    /**
     * 生成电影 NFO: {@code <视频文件名>.nfo}（与 STRM 文件同名，不含视频后缀）
     */
    public void generateMovieNfo(MediaInfo info, Path destFile, Path outputDir, boolean forceOverwrite) throws IOException {
        String nfoContent = movieBuilder.buildNfo(info);
        String nfoName = stripExtension(destFile.getFileName().toString()) + ".nfo";
        Path nfoFile = destFile.resolveSibling(nfoName);
        writeNfo(nfoFile, nfoContent, forceOverwrite);
        log.info("生成电影 NFO: {}", nfoFile);
    }

    /**
     * 生成剧集 NFO:
     * - {@code tvshow.nfo} (剧集根目录，固定命名)
     * - {@code season.nfo} (季目录内，固定命名)
     * - {@code <episodedetails>.nfo} (与 STRM 文件同名)
     */
    public void generateTvNfo(MediaInfo info, Path destFile, Path outputDir, boolean forceOverwrite) throws IOException {
        // 剧集 NFO → 放在剧集根目录（{show_name} ({year})/），即 Season XX 的父目录
        String tvshowNfo = tvShowBuilder.buildNfo(info);
        Path showRoot = destFile.getParent().getParent();
        Path tvshowNfoFile = showRoot.resolve("tvshow.nfo");
        writeNfo(tvshowNfoFile, tvshowNfo, forceOverwrite);
        log.info("生成剧集 NFO (系列): {}", tvshowNfoFile);

        // 季 NFO → 放在季目录内
        String seasonNfo = seasonBuilder.buildNfo(info);
        Path seasonNfoFile = destFile.getParent().resolve("season.nfo");
        writeNfo(seasonNfoFile, seasonNfo, forceOverwrite);
        log.info("生成剧集 NFO (季): {}", seasonNfoFile);

        // 单集 NFO → 与 STRM 文件同名
        String episodeNfo = episodeBuilder.buildNfo(info);
        String episodeNfoName = stripExtension(destFile.getFileName().toString()) + ".nfo";
        Path episodeNfoFile = destFile.resolveSibling(episodeNfoName);
        writeNfo(episodeNfoFile, episodeNfo, forceOverwrite);
        log.info("生成剧集 NFO (单集): {}", episodeNfoFile);
    }

    // ==================== Helpers ====================

    private String stripExtension(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private void writeNfo(Path nfoFile, String content, boolean forceOverwrite) throws IOException {
        Path parent = nfoFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // 按目标文件路径加锁 + 临时文件原子替换，避免同一 NFO（如 tvshow.nfo）被并发刮削任务写坏
        ScrapeFileLock.withLock(nfoFile, () -> {
            if (Files.exists(nfoFile) && !forceOverwrite) {
                log.debug("NFO 文件已存在，跳过: {}", nfoFile);
                return;
            }
            Path tmpFile = nfoFile.resolveSibling(nfoFile.getFileName().toString() + ".tmp");
            try {
                Files.write(tmpFile, content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(tmpFile, nfoFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        });
    }
}
