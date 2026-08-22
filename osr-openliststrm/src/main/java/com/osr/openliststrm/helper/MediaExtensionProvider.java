package com.osr.openliststrm.helper;

import com.osr.openliststrm.config.OpenlistConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 视频 / 字幕扩展名清单的唯一来源，读 sys_config 的
 * {@code openlist.strm.video.extensions} 与 {@code openlist.strm.subtitle.extensions}。
 *
 * <p><b>缓存的 key 是配置原文本身，不是配置键名</b>——这不是省事，是这个类存在的主要理由。
 * 前身 {@code SysDictDataHelper} 按字典类型名缓存，失效依赖一个需要有人记得调用的
 * {@code refreshCache(String)}，而那个方法<b>全项目零调用方</b>：用户在字典管理页加一个
 * 扩展名，保存成功、列表刷新、一切正常，业务侧却一直用着旧集合，非重启后端不生效。
 * 它不报错、不告警，唯一的现象是"某类文件就是不生成 STRM"。改成按原文缓存后，
 * 配置一变 key 就不同、自动重算，<b>结构上不存在"忘记让缓存失效"这回事</b>。</p>
 *
 * <p>取原文很便宜：{@code selectConfigByKey} 那层已有 {@code CacheUtils} 缓存，
 * 且 {@code SysConfigServiceImpl#updateConfig} 会在写入时刷新它。这里再缓存一层，
 * 省的是每次调用都要做的 split + toLowerCase——目录遍历是热路径，每个文件都要问一次。</p>
 *
 * @author Jack
 */
@Component
public class MediaExtensionProvider {

    @Autowired
    private OpenlistConfig config;

    private volatile Parsed video = new Parsed(null, Set.of());

    private volatile Parsed subtitle = new Parsed(null, Set.of());

    /** 一次解析结果连同它的来源原文；原文对不上就说明配置变了，需要重算 */
    private record Parsed(String raw, Set<String> extensions) {
    }

    /** 视频扩展名集合，小写、不带点 */
    public Set<String> videoExtensions() {
        String raw = config.getVideoExtensions();
        Parsed cached = video;
        // 用 Objects.equals：初始哨兵的 raw 是 null，直接调 equals 会 NPE
        if (!Objects.equals(cached.raw(), raw)) {
            cached = new Parsed(raw, parse(raw));
            video = cached;
        }
        return cached.extensions();
    }

    /** 字幕扩展名集合，小写、不带点 */
    public Set<String> subtitleExtensions() {
        String raw = config.getSubtitleExtensions();
        Parsed cached = subtitle;
        if (!Objects.equals(cached.raw(), raw)) {
            cached = new Parsed(raw, parse(raw));
            subtitle = cached;
        }
        return cached.extensions();
    }

    /**
     * 逗号分隔的清单拆成小写集合。用户很可能照着自己的习惯填成 {@code .mp4, .mkv}，
     * 所以前导点与空白都要容忍——填错一个点就整类文件不处理，而他看不到任何提示。
     */
    private Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase())
                .map(s -> s.startsWith(".") ? s.substring(1) : s)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
