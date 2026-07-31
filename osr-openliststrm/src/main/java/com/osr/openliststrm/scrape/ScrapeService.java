package com.osr.openliststrm.scrape;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
     * 删除刮削产生的文件（NFO + 图片），不删除 STRM 等媒体文件。
     * <p>
     * 电影：删除 NFO + 7 种图片（目录独有）
     * 剧集：删除单集 NFO；季 NFO 和季海报仅在同季无其他记录时删除；
     *       剧集根目录文件（tvshow.nfo + 图片）在同剧无其他记录时删除
     *
     * @param detail 重命名明细记录
     * @return 删除的文件数量
     */
    public int deleteScrapeFiles(RenameDetailPlus detail) {
        if (detail == null || detail.getNewPath() == null || detail.getNewName() == null) {
            return 0;
        }
        int deleted = 0;
        Path dir = Paths.get(detail.getNewPath());
        String baseName = stripExtension(detail.getNewName());

        if ("tv".equals(detail.getMediaType())) {
            // 单集 NFO
            deleted += deleteIfExists(dir.resolve(baseName + ".nfo"));

            // 检查同季是否还有其他记录
            boolean hasSiblingInSeason = hasSiblingInSameSeason(detail);
            if (!hasSiblingInSeason) {
                deleted += deleteIfExists(dir.resolve("season.nfo"));
                deleted += deleteIfExists(dir.resolve("season-poster.jpg"));
            }

            // 检查同剧是否还有其他记录（跨季）
            boolean hasSiblingInShow = hasSiblingInSameShow(detail);
            if (!hasSiblingInShow) {
                // 删除剧集根目录共享文件
                Path showRoot = dir.getParent();
                if (showRoot != null) {
                    deleted += deleteIfExists(showRoot.resolve("tvshow.nfo"));
                    String[] showImages = {"poster.jpg", "fanart.jpg", "clearlogo.png",
                            "banner.jpg", "clearart.png", "landscape.jpg", "thumb.jpg"};
                    for (String img : showImages) {
                        deleted += deleteIfExists(showRoot.resolve(img));
                    }
                }
            }
        } else {
            // 电影：NFO + 图片
            deleted += deleteIfExists(dir.resolve(baseName + ".nfo"));
            String[] imageFiles = {"poster.jpg", "fanart.jpg", "clearlogo.png",
                    "banner.jpg", "clearart.png", "landscape.jpg", "thumb.jpg"};
            for (String img : imageFiles) {
                deleted += deleteIfExists(dir.resolve(img));
            }
        }

        if (deleted > 0) {
            updateScrapeStatus(detail.getId(), "0", null);
            log.info("已删除 {} 个刮削文件，detailId={}", deleted, detail.getId());
        }
        return deleted;
    }

    /**
     * 检查同季目录是否还有其他重命名记录（排除当前记录）
     */
    private boolean hasSiblingInSameSeason(RenameDetailPlus detail) {
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RenameDetailPlus> qw =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            qw.eq("new_path", detail.getNewPath())
                    .eq("media_type", "tv")
                    .ne("id", detail.getId());
            return renameDetailService.count(qw) > 0;
        } catch (Exception e) {
            log.warn("检查同季记录失败: {}", e.getMessage());
            return true; // 查询失败时保守处理
        }
    }

    /**
     * 检查同剧（跨季）是否还有其他重命名记录（排除当前记录）。
     * 通过 new_path 前缀匹配剧集根目录（Season XX 的父目录）来判断。
     */
    private boolean hasSiblingInSameShow(RenameDetailPlus detail) {
        try {
            Path showRoot = Paths.get(detail.getNewPath()).getParent();
            if (showRoot == null) return true;
            String showRootStr = showRoot.toString();

            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RenameDetailPlus> qw =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            qw.likeRight("new_path", showRootStr)
                    .eq("media_type", "tv")
                    .ne("id", detail.getId());
            return renameDetailService.count(qw) > 0;
        } catch (Exception e) {
            log.warn("检查同剧记录失败: {}", e.getMessage());
            return true; // 查询失败时保守处理
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

    private String stripExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private void updateScrapeStatus(Integer detailId, String status, String msg) {
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
