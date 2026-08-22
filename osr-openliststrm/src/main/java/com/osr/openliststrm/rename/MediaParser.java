package com.osr.openliststrm.rename;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.openai.OpenAIClient;
import com.osr.openliststrm.rename.extractor.Extractor;
import com.osr.openliststrm.rename.extractor.impl.CodecExtractor;
import com.osr.openliststrm.rename.extractor.impl.ResolutionExtractor;
import com.osr.openliststrm.rename.extractor.impl.SourceAndGroupExtractor;
import com.osr.openliststrm.rename.extractor.impl.YearSeasonEpisodeExtractor;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.rename.processor.TitleProcessor;
import com.osr.openliststrm.rename.render.PebbleRenderer;
import com.osr.openliststrm.tmdb.TMDbClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author Jack
 * @Date 2025/8/12 16:54
 * @Version 1.0.0
 */
@Slf4j
public class MediaParser {
    private final List<Extractor> extractors = new ArrayList<>();
    private final TitleProcessor titleProcessor = new TitleProcessor();
    private final TMDbClient tmdbClient;
    private final OpenAIClient openAIClient;
    private final PebbleRenderer renderer = new PebbleRenderer();

    public MediaParser(TMDbClient tmdbClient, OpenAIClient openAIClient) {
        this.tmdbClient = tmdbClient;
        this.openAIClient = openAIClient;
        extractors.add(new ResolutionExtractor());
        extractors.add(new CodecExtractor());
        extractors.add(new SourceAndGroupExtractor());
        extractors.add(new YearSeasonEpisodeExtractor());
    }

    public String rename(String filename, String template) {
        MediaInfo info = parse(filename);
        return render(info, template);
    }

    public MediaInfo parse(String filename) {
        return parse(filename, null, null);
    }

    public MediaInfo parse(String filename, String title, String year) {
        log.info("开始重命名文件名: {}", filename);
        MediaInfo info = extractBase(filename);

        if (StringUtils.isNotEmpty(title)) info.setTitle(title);
        if (StringUtils.isNotEmpty(year)) info.setYear(year);

        if (tmdbClient != null) {
            tmdbClient.enrich(info);
        }

        if (openAIClient != null && needsAI(info)) {
            try {
                log.info("使用AI识别: {}", filename);
                boolean updated = openAIClient.enrich(info, filename);
                if (updated && tmdbClient != null) {
                    tmdbClient.enrich(info);
                }
            } catch (Exception e) {
                // 原先上下文打在 INFO、堆栈打在 ERROR，一件事被拆进两个级别：
                // 只看 sys-error.log 不知道是哪个文件，只看 INFO 又没有原因。
                // 降为 WARN：AI 识别失败会退回常规解析流程，不是需要人介入的故障。
                log.warn("使用AI识别失败: {}", filename, e);
            }
        }

        log.debug("文件名称: {} 识别结果info: {}", filename, info);
        return info;
    }

    /**
     * 已知 tmdbId 时按 tmdbId 直接拉取详情，不再走标题模糊搜索。
     * 用于"重新刮削"场景：复用此前（可能经过人工修正）确定的 tmdbId，避免续集/重制版/同名作品被重新匹配错。
     */
    public MediaInfo parseWithKnownTmdbId(String filename, String tmdbId, String mediaType) {
        log.info("开始重命名文件名（已知tmdbId={}）: {}", tmdbId, filename);
        MediaInfo info = extractBase(filename);

        if (tmdbClient != null && StringUtils.isNotBlank(tmdbId)) {
            String type = "movie".equals(mediaType) ? "movie" : "tv";
            tmdbClient.enrichByTmdbId(info, type, tmdbId);
        }

        log.debug("文件名称: {} 识别结果info(已知tmdbId): {}", filename, info);
        return info;
    }

    /**
     * 只做本地正则抽取，<b>不发任何网络请求</b>（不查 TMDb、不调 AI）。
     * <p>
     * 供 PT 订阅的 RSS 轮询使用：一轮轮询有几十上百条种子标题，逐条查 TMDb 会打爆配额、
     * 把一次轮询拖成几分钟，而且其中大部分种子根本不匹配任何订阅，那些调用是纯浪费。
     * 匹配阶段只需要标题/年份/季/集/分辨率这些能从标题正则抽出来的信息。
     * </p>
     *
     * @param name 种子标题或文件名，允许没有扩展名
     */
    public MediaInfo parseLocal(String name) {
        return extractBase(name, false);
    }

    private MediaInfo extractBase(String filename) {
        return extractBase(filename, true);
    }

    /**
     * @param stripExtension 是否按"文件名"处理，截取最后一个 . 之后的内容作为扩展名。
     *                        种子/RSS 标题（{@link #parseLocal}）不是文件名，标题里出现的 "."
     *                        只是普通分隔符（如 "...H265-FLUX" "...S01E05"），不能当扩展名截断，
     *                        否则会把发布组、季集号等关键信息连同"扩展名"一起丢弃。
     */
    private MediaInfo extractBase(String filename, boolean stripExtension) {
        String extension = "";
        String baseName = filename;
        if (stripExtension && filename.lastIndexOf('.') != -1) {
            extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
            baseName = filename.substring(0, filename.lastIndexOf('.')).trim();
        }

        // 预处理文件名
        String norm = normalize(baseName);

        MediaInfo info = new MediaInfo(filename);
        info.setExtension(extension);
        String remaining = norm;
        for (Extractor ex : extractors) {
            remaining = ex.extract(remaining, info);
        }
        titleProcessor.processTitle(remaining, info);
        return info;
    }

    public String render(MediaInfo info, String template) {
        return renderer.render(info, template);
    }

    private String normalize(String s) {
        // 1. 处理特殊字符：将 ￡ (全角英镑)、_ (下划线) 替换为空格
        // 并且在 . (点) 后面是字母数字时替换为空格
        String t = s.replace('￡', ' ')
                .replace('_', ' ')
                .replaceAll("\\.(?=[A-Za-z0-9])", " ");

        // 2. 在中英文/数字之间强制插入空格 (解决中文粘连问题)
        t = t.replaceAll("([\\u4e00-\\u9fa5])(?=[A-Za-z0-9])", "$1 ");
        t = t.replaceAll("(?<=[A-Za-z0-9])([\\u4e00-\\u9fa5])", " $1");

        // 3. 处理括号
        t = t.replaceAll("([\\[\\]【】\\(\\)])", " $1 ");

        return t.replaceAll("\\s+", " ").trim();
    }

    private boolean needsAI(MediaInfo info) {
        return StringUtils.isBlank(info.getTmdbId());
    }
}