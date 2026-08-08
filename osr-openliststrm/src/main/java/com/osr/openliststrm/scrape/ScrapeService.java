package com.osr.openliststrm.scrape;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.rename.cleanup.ArtifactPaths;
import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;

/**
 * 刮削服务：重命名完成后异步生成 NFO 和下载图片。
 * 刮削失败不影响重命名结果。
 */
@Slf4j
@Service
public class ScrapeService {

    @Autowired
    private NfoGenerator nfoGenerator;

    @Autowired
    private MediaImageDownloader imageDownloader;

    @Autowired
    private OpenlistConfig config;

    @Autowired
    private IRenameDetailPlusService renameDetailService;

    /**
     * 异步执行刮削（NFO + 图片）。
     *
     * @param detailId    重命名明细 ID
     * @param info        媒体信息（已填充 TMDb 元数据）
     * @param mediaType   "movie" 或 "tv"
     * @param destFile    目标文件路径
     * @param outputDir   输出目录（系列级目录）
     * @param scrapeEnabled 是否启用刮削
     * @param scrapeNfo   是否生成 NFO
     * @param scrapeImages 是否下载图片
     * @param forceOverwrite 是否强制覆盖已有文件
     */
    public void scrapeAsync(Integer detailId, MediaInfo info, String mediaType,
                            Path destFile, Path outputDir,
                            String scrapeEnabled, String scrapeNfo, String scrapeImages,
                            boolean forceOverwrite) {
        if (!"1".equals(scrapeEnabled)) {
            return;
        }

        AsyncManager.me().execute(() -> {
            try {
                boolean anyScraped = false;

                if ("1".equals(scrapeNfo)) {
                    generateNfo(info, mediaType, destFile, outputDir, forceOverwrite);
                    anyScraped = true;
                }

                if ("1".equals(scrapeImages)) {
                    downloadImages(info, mediaType, outputDir, forceOverwrite);
                    anyScraped = true;
                }

                if (anyScraped && detailId != null) {
                    updateScrapeStatus(detailId, "1", null);
                }
            } catch (Exception e) {
                log.warn("刮削失败: {}", e.getMessage());
                if (detailId != null) {
                    String msg = e.getMessage();
                    if (msg != null && msg.length() > 500) {
                        msg = msg.substring(0, 500);
                    }
                    updateScrapeStatus(detailId, "2", msg);
                }
            }
        });
    }

    private void generateNfo(MediaInfo info, String mediaType, Path destFile, Path outputDir, boolean forceOverwrite) throws Exception {
        if ("tv".equals(mediaType)) {
            nfoGenerator.generateTvNfo(info, destFile, outputDir, forceOverwrite);
        } else {
            nfoGenerator.generateMovieNfo(info, destFile, outputDir, forceOverwrite);
        }
    }

    private void downloadImages(MediaInfo info, String mediaType, Path outputDir, boolean forceOverwrite) throws Exception {
        if ("tv".equals(mediaType)) {
            // 剧集图片下载到剧集根目录 (Season XX 的父目录)
            Path showRoot = outputDir.getParent();
            if (showRoot != null) {
                imageDownloader.downloadTvImages(info, showRoot, forceOverwrite);
                // 季海报下载到季目录
                imageDownloader.downloadSeasonPoster(info, outputDir, forceOverwrite);
            }
        } else {
            imageDownloader.downloadMovieImages(info, outputDir, forceOverwrite);
        }
    }

    /**
     * 删除刮削文件时的附加约束。
     *
     * @param excludeDetailIds 判定"同季/同剧是否还有别的记录"时额外排除的 detail id。
     *                         批量清理必须把整批 id 都传进来：逐条调用时兄弟计数是边删边变的，
     *                         不排除整批的话前几条会认为"还有兄弟"而跳过 season.nfo/tvshow.nfo，
     *                         只靠最后一条兜底——中途任何一条失败就留下没人认领的共享元数据。
     * @param keepDir          该目录下的季级/电影级共享文件要保留（重命名换位时新旧同目录的场景）
     * @param keepShowRoot     该剧集根目录下的共享文件要保留（重命名换位时新旧同剧的场景）
     */
    public record DeleteOptions(Collection<Integer> excludeDetailIds, Path keepDir, Path keepShowRoot) {

        public static final DeleteOptions DEFAULT = new DeleteOptions(Set.of(), null, null);

        public DeleteOptions {
            excludeDetailIds = excludeDetailIds == null ? Set.of() : Set.copyOf(excludeDetailIds);
        }

        public static DeleteOptions excluding(Collection<Integer> ids) {
            return new DeleteOptions(ids, null, null);
        }

        public static DeleteOptions keeping(Path dir, Path showRoot) {
            return new DeleteOptions(Set.of(), dir, showRoot);
        }
    }

    /**
     * 删除刮削产生的文件（NFO + 图片），不删除 STRM 等媒体文件。
     *
     * @param detail 重命名明细记录
     * @return 删除的文件数量
     */
    public int deleteScrapeFiles(RenameDetailPlus detail) {
        return deleteScrapeFiles(detail, DeleteOptions.DEFAULT);
    }

    /**
     * 删除刮削产生的文件（NFO + 图片），不删除 STRM 等媒体文件。
     * <p>
     * 电影：删除同名 NFO；7 种图片仅在同目录无其他记录时删除
     * 剧集：删除单集 NFO；季 NFO 和季海报仅在同季无其他记录时删除；
     *       剧集根目录文件（tvshow.nfo + 图片）在同剧无其他记录时删除
     * <p>
     * 同名 NFO 与媒体文件一一对应，任何情况下都跟着走；共享文件（季/剧/电影目录级）
     * 只有在确认没有别的记录还需要它时才删。
     *
     * @param detail  重命名明细记录
     * @param options 兄弟判定的排除项与保留项，见 {@link DeleteOptions}
     * @return 删除的文件数量
     */
    public int deleteScrapeFiles(RenameDetailPlus detail, DeleteOptions options) {
        int deleted = 0;
        for (Path file : resolveScrapeFiles(detail, options)) {
            deleted += deleteIfExists(file);
        }
        if (deleted > 0) {
            updateScrapeStatus(detail.getId(), "0", null);
            log.info("已删除 {} 个刮削文件，detailId={}", deleted, detail.getId());
        }
        return deleted;
    }

    /**
     * 解析出该记录名下应当被删除的刮削文件路径（不判断文件是否真的存在、不做任何删除）。
     * <p>
     * 删除与"预览将删除什么"必须走同一份判定，否则确认框里给用户看的清单
     * 和真正删掉的东西会对不上——那比不给预览更糟。
     */
    public java.util.List<Path> resolveScrapeFiles(RenameDetailPlus detail, DeleteOptions options) {
        if (detail == null || detail.getNewPath() == null || detail.getNewName() == null) {
            return java.util.List.of();
        }
        DeleteOptions opts = options == null ? DeleteOptions.DEFAULT : options;
        java.util.List<Path> files = new java.util.ArrayList<>();
        Path dir = Paths.get(detail.getNewPath()).toAbsolutePath().normalize();
        String baseName = ArtifactPaths.stripExtension(detail.getNewName());

        // 与媒体文件同名的 NFO：一一对应，无条件跟着删
        files.add(dir.resolve(baseName + ".nfo"));

        if ("tv".equals(detail.getMediaType())) {
            if (!samePath(dir, opts.keepDir()) && !hasSiblingInSameSeason(detail, opts)) {
                files.add(dir.resolve("season.nfo"));
                files.add(dir.resolve("season-poster.jpg"));
            }

            Path showRoot = dir.getParent();
            if (showRoot != null && !samePath(showRoot, opts.keepShowRoot()) && !hasSiblingInSameShow(detail, opts)) {
                files.add(showRoot.resolve("tvshow.nfo"));
                for (String img : ArtifactPaths.SHARED_IMAGES) {
                    files.add(showRoot.resolve(img));
                }
            }
        } else {
            // 电影目录一般一片一目录，但同一部片的多个版本（1080p/4K）会落在同一目录，
            // 此时海报是共享的，删一个版本不该把另一个版本的图连坐
            if (!samePath(dir, opts.keepDir()) && !hasSiblingInSameDir(detail, opts)) {
                for (String img : ArtifactPaths.SHARED_IMAGES) {
                    files.add(dir.resolve(img));
                }
            }
        }
        return files;
    }

    private static boolean samePath(Path a, Path b) {
        return a != null && b != null && a.equals(b.toAbsolutePath().normalize());
    }

    /**
     * 检查同季目录是否还有其他重命名记录（排除当前记录与 options 指定的排除项）
     */
    private boolean hasSiblingInSameSeason(RenameDetailPlus detail, DeleteOptions options) {
        return hasSibling(detail, options, qw -> qw.eq("new_path", detail.getNewPath()).eq("media_type", "tv"));
    }

    /**
     * 检查同一目录下是否还有其他重命名记录（电影用）
     */
    private boolean hasSiblingInSameDir(RenameDetailPlus detail, DeleteOptions options) {
        return hasSibling(detail, options, qw -> qw.eq("new_path", detail.getNewPath()));
    }

    /**
     * 检查同剧（跨季）是否还有其他重命名记录（排除当前记录）。
     * 通过 new_path 前缀匹配剧集根目录（Season XX 的父目录）来判断。
     * <p>
     * 前缀必须补上路径分隔符再匹配，否则 {@code /电视剧/国产剧/三体} 会连
     * {@code /电视剧/国产剧/三体2} 一起算进来，"同剧还有别的记录"恒为真，
     * tvshow.nfo 与剧集图片永远删不掉。值里的 LIKE 通配符也要转义——
     * 路径中的下划线在 LIKE 里是"任意单字符"。
     */
    private boolean hasSiblingInSameShow(RenameDetailPlus detail, DeleteOptions options) {
        Path showRoot = Paths.get(detail.getNewPath()).getParent();
        if (showRoot == null) {
            return true;
        }
        String showRootStr = showRoot.toString();
        String prefix = ArtifactPaths.escapeLike(showRootStr + showRoot.getFileSystem().getSeparator());
        return hasSibling(detail, options, qw -> qw
                .eq("media_type", "tv")
                .and(w -> w.eq("new_path", showRootStr).or().likeRight("new_path", prefix)));
    }

    private boolean hasSibling(RenameDetailPlus detail, DeleteOptions options,
                               java.util.function.Consumer<QueryWrapper<RenameDetailPlus>> criteria) {
        try {
            QueryWrapper<RenameDetailPlus> qw = new QueryWrapper<>();
            criteria.accept(qw);
            qw.ne(detail.getId() != null, "id", detail.getId());
            Collection<Integer> excluded = options.excludeDetailIds();
            qw.notIn(!excluded.isEmpty(), "id", excluded);
            return renameDetailService.count(qw) > 0;
        } catch (Exception e) {
            log.warn("检查同目录/同剧记录失败: {}", e.getMessage());
            return true; // 查询失败时保守处理：宁可留下共享元数据，也不误删还在用的
        }
    }

    private int deleteIfExists(Path file) {
        try {
            if (Files.exists(file)) {
                Files.delete(file);
                log.debug("已删除刮削文件: {}", file);
                return 1;
            }
        } catch (IOException e) {
            log.warn("删除刮削文件失败: {}", file, e);
        }
        return 0;
    }

    private void updateScrapeStatus(Integer detailId, String status, String msg) {
        if (detailId == null) {
            // 重命名换位时用旧路径快照调用，快照可能不带 id，这里没有状态可更新
            return;
        }
        try {
            UpdateWrapper<RenameDetailPlus> uw = new UpdateWrapper<>();
            uw.eq("id", detailId)
                    .set("scrape_status", status);
            if (StringUtils.isNotBlank(msg)) {
                uw.set("scrape_msg", msg);
            }
            renameDetailService.update(null, uw);
            log.debug("更新刮削状态: detailId={}, status={}", detailId, status);
        } catch (Exception e) {
            log.warn("更新刮削状态失败: {}", e.getMessage());
        }
    }
}
