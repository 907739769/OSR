package com.osr.openliststrm.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 从 TMDb 下载媒体图片（海报、背景图等）。
 * 使用项目已有的 OkHttp 客户端，避免引入新依赖。
 */
@Slf4j
@Component
public class MediaImageDownloader {

    private static final String TMDb_IMG_HOST = "https://image.tmdb.org/t/p/";
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private final OpenlistConfig config;

    public MediaImageDownloader(OpenlistConfig config,
                                @Qualifier("sharedOkHttpClient") OkHttpClient sharedClient) {
        this.config = config;
        this.client = sharedClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 下载电影图片: poster.jpg, fanart.jpg, clearlogo.png, banner.jpg, clearart.png, landscape.jpg, thumb.jpg
     * 合并 details 和 images 数据到一个节点中，方便统一处理
     */
    public void downloadMovieImages(MediaInfo info, Path outputDir, boolean forceOverwrite) {
        JsonNode details = getDetails(info);
        if (details == null) return;

        // 合并 images 数据到 details 中
        details = mergeImages(details, getImagesFromMetadata(info));
        downloadAllImages(details, outputDir, forceOverwrite);
    }

    /**
     * 下载剧集图片: poster.jpg, fanart.jpg, clearlogo.png, banner.jpg, clearart.png, landscape.jpg, thumb.jpg
     * 合并 details 和 images 数据到一个节点中，方便统一处理
     */
    public void downloadTvImages(MediaInfo info, Path outputDir, boolean forceOverwrite) {
        JsonNode details = getDetails(info);
        if (details == null) return;

        // 合并 images 数据到 details 中
        details = mergeImages(details, getImagesFromMetadata(info));
        downloadAllImages(details, outputDir, forceOverwrite);
    }

    /**
     * 并发下载 poster/fanart/clearlogo/banner/clearart/landscape/thumb 最多7张图片。
     * 各自写入不同目标文件（downloadImageLocked 按路径加锁防交错写入），互不依赖，
     * 之前串行下载会导致耗时随图片数量线性叠加。
     */
    private void downloadAllImages(JsonNode details, Path outputDir, boolean forceOverwrite) {
        List<Runnable> tasks = List.of(
                () -> downloadImage(details, "poster", "poster.jpg", outputDir, forceOverwrite),
                () -> downloadImage(details, "backdrop", "fanart.jpg", outputDir, forceOverwrite),
                () -> downloadLogo(details, outputDir, forceOverwrite),
                () -> downloadImageByIndex(details, "backdrops", "banner.jpg", 1, forceOverwrite, outputDir),
                () -> downloadImageByIndex(details, "logos", "clearart.png", 1, forceOverwrite, outputDir),
                () -> downloadImageByIndex(details, "backdrops", "landscape.jpg", 2, forceOverwrite, outputDir),
                () -> downloadImageByIndex(details, "posters", "thumb.jpg", 1, forceOverwrite, outputDir)
        );
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = tasks.stream()
                    .map(t -> CompletableFuture.runAsync(Threads.wrap(t), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    /**
     * 下载季级别图片: season-poster.jpg 到季目录
     */
    public void downloadSeasonPoster(MediaInfo info, Path seasonDir, boolean forceOverwrite) {
        if (info.getMetadata() == null) return;
        Object siObj = info.getMetadata().get("season_images");
        if (!(siObj instanceof JsonNode)) return;
        JsonNode seasonImages = (JsonNode) siObj;
        JsonNode posters = seasonImages.path("posters");
        if (!posters.isArray() || posters.size() == 0) return;

        String url = selectImageFromList(posters);
        if (url == null) return;

        downloadImageLocked(url, seasonDir.resolve("season-poster.jpg"), forceOverwrite, "季海报");
    }

    /**
     * TMDb 图片 CDN 基础地址（不含末尾斜杠，TMDb 返回的 file_path 本身以 "/" 开头），
     * 尺寸段可通过 openlist.tmdb.image.size 配置（默认 original）。
     */
    private String imgBase() {
        return TMDb_IMG_HOST + config.getTmdbImageSize();
    }

    private JsonNode mergeImages(JsonNode details, JsonNode imagesNode) {
        if (imagesNode == null) return details;
        try {
            ObjectNode merged = MAPPER.valueToTree(details);
            if (imagesNode.has("posters")) merged.set("posters", imagesNode.get("posters"));
            if (imagesNode.has("backdrops")) merged.set("backdrops", imagesNode.get("backdrops"));
            if (imagesNode.has("logos")) merged.set("logos", imagesNode.get("logos"));
            return merged;
        } catch (Exception e) {
            log.warn("合并 images 数据失败: {}", e.getMessage());
            return details;
        }
    }

    private void downloadImage(JsonNode details, String type, String filename, Path outputDir, boolean forceOverwrite) {
        try {
            String imgUrl = extractImageUrl(details, type);
            if (imgUrl == null) {
                log.debug("未找到 {} 图片", type);
                return;
            }
            downloadImageLocked(imgUrl, outputDir.resolve(filename), forceOverwrite, type);
        } catch (Exception e) {
            log.warn("下载 {} 图片失败: {}", type, e.getMessage());
        }
    }

    private void downloadLogo(JsonNode details, Path outputDir, boolean forceOverwrite) {
        try {
            String imgUrl = extractLogoUrl(details, 0);
            if (imgUrl == null) return;
            downloadImageLocked(imgUrl, outputDir.resolve("clearlogo.png"), forceOverwrite, "clearlogo");
        } catch (Exception e) {
            log.warn("下载 clearlogo 图片失败: {}", e.getMessage());
        }
    }

    /**
     * 从图片列表中按索引下载图片，如果索引超出范围则回退到第一张
     */
    private void downloadImageByIndex(JsonNode details, String imagesKey, String filename,
                                      int preferredIndex, boolean forceOverwrite, Path outputDir) {
        try {
            JsonNode imagesArray = details.path(imagesKey);
            if (!imagesArray.isArray() || imagesArray.size() == 0) return;

            // 如果有足够的图片，使用指定索引；否则使用第一张
            int idx = Math.min(preferredIndex, imagesArray.size() - 1);
            String url = selectImageFromListAt(imagesArray, idx);
            if (url == null) return;

            downloadImageLocked(url, outputDir.resolve(filename), forceOverwrite, filename);
        } catch (Exception e) {
            log.warn("下载 {} 图片失败: {}", filename, e.getMessage());
        }
    }

    /**
     * 按目标文件路径加锁 + 临时文件下载后原子替换，避免同一图片文件（如 season-poster.jpg）
     * 被多个并发刮削任务同时写入导致内容交错/半下载文件。
     */
    private void downloadImageLocked(String urlStr, Path target, boolean forceOverwrite, String logLabel) {
        try {
            ScrapeFileLock.withLock(target, () -> {
                if (Files.exists(target) && !forceOverwrite) {
                    log.debug("{} 已存在，跳过: {}", logLabel, target);
                    return;
                }
                Path tmpFile = target.resolveSibling(target.getFileName().toString() + ".tmp");
                try {
                    downloadToFile(urlStr, tmpFile);
                    Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    log.info("下载 {} 图片: {} -> {}", logLabel, urlStr, target);
                } finally {
                    Files.deleteIfExists(tmpFile);
                }
            });
        } catch (Exception e) {
            log.warn("下载 {} 图片失败: {}", logLabel, e.getMessage());
        }
    }

    private String extractImageUrl(JsonNode details, String type) {
        // images 数据已在 downloadMovieImages/downloadTvImages 中合并到 details 顶层
        // 映射: poster -> posters, backdrop -> backdrops
        String imagesKey = type.equals("poster") ? "posters" : 
                           type.equals("backdrop") ? "backdrops" : type;
        
        if (details.has(imagesKey) && details.get(imagesKey).isArray() && details.get(imagesKey).size() > 0) {
            // 按语言偏好选择: zh > en > null
            String preferredLang = config.getTmdbImageLanguage();
            String[] langPriority = {preferredLang, "en", ""};
            
            for (String lang : langPriority) {
                for (JsonNode file : details.get(imagesKey)) {
                    String filePath = file.path("file_path").asText(null);
                    String isoLang = file.path("iso_639_1").asText("");
                    if (filePath != null && lang.equals(isoLang)) {
                        return imgBase() +filePath;
                    }
                }
            }
            // 回退到第一个
            JsonNode first = details.get(imagesKey).get(0);
            String filePath = first.path("file_path").asText(null);
            if (filePath != null) {
                return imgBase() +filePath;
            }
        }
        
        // 回退: 从 details 顶级字段获取 (poster_path, backdrop_path)
        if ("backdrop".equals(type) && details.has("backdrop_path")) {
            return imgBase() +details.get("backdrop_path").asText();
        }
        if ("poster".equals(type) && details.has("poster_path")) {
            return imgBase() +details.get("poster_path").asText();
        }
        return null;
    }

    private String extractLogoUrl(JsonNode details, int preferredIndex) {
        // logos 数据已在 downloadMovieImages/downloadTvImages 中合并到 details 顶层
        if (details.has("logos") && details.get("logos").isArray() && details.get("logos").size() > 0) {
            return selectImageFromListAt(details.get("logos"), preferredIndex);
        }
        return null;
    }

    /**
     * 从图片列表中选择最佳图片（按语言偏好），返回完整 URL
     */
    private String selectImageFromList(JsonNode imageArray) {
        return selectImageFromListAt(imageArray, 0);
    }

    /**
     * 从图片列表中选择指定索引的图片（按语言偏好），返回完整 URL
     * 如果 preferredIndex 超出范围，则回退到第一张
     */
    private String selectImageFromListAt(JsonNode imageArray, int preferredIndex) {
        if (!imageArray.isArray() || imageArray.size() == 0) return null;

        String preferredLang = config.getTmdbImageLanguage();
        String[] langPriority = {preferredLang, "en", ""};

        // 首先尝试在 preferredIndex 附近按语言偏好选择
        int maxIdx = Math.min(preferredIndex, imageArray.size() - 1);

        // 从 preferredIndex 开始向前查找匹配语言的图片
        for (int i = maxIdx; i < imageArray.size(); i++) {
            JsonNode file = imageArray.get(i);
            String filePath = file.path("file_path").asText(null);
            if (filePath == null) continue;
            String isoLang = file.path("iso_639_1").asText("");
            if (preferredLang.equals(isoLang)) {
                return imgBase() +filePath;
            }
        }
        // 再从头查找 en 语言
        for (int i = maxIdx; i < imageArray.size(); i++) {
            JsonNode file = imageArray.get(i);
            String filePath = file.path("file_path").asText(null);
            if (filePath == null) continue;
            String isoLang = file.path("iso_639_1").asText("");
            if ("en".equals(isoLang)) {
                return imgBase() +filePath;
            }
        }
        // 回退: 使用 preferredIndex 处的图片
        JsonNode selected = imageArray.get(maxIdx);
        String filePath = selected.path("file_path").asText(null);
        if (filePath != null) {
            return imgBase() +filePath;
        }
        // 最终回退: 使用第一张
        filePath = imageArray.get(0).path("file_path").asText(null);
        return filePath != null ? imgBase() +filePath : null;
    }

    private void downloadToFile(String urlStr, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Request request = new Request.Builder().url(urlStr).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            try (InputStream in = response.body().byteStream();
                 OutputStream out = Files.newOutputStream(target)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
        }
    }

    private JsonNode getDetails(MediaInfo info) {
        if (info.getMetadata() == null) return null;
        Object details = info.getMetadata().get("details");
        if (details instanceof JsonNode) {
            return (JsonNode) details;
        }
        return null;
    }

    private JsonNode getImagesFromMetadata(MediaInfo info) {
        if (info.getMetadata() == null) return null;
        Object images = info.getMetadata().get("images");
        if (images instanceof JsonNode) {
            return (JsonNode) images;
        }
        return null;
    }
}
