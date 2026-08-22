package com.osr.openliststrm.helper;

import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @Author Jack
 * @Date 2025/7/16 20:51
 * @Version 1.0.0
 */
@Slf4j
@Component
public class OpenListHelper {

    /** 关闭临时目录过滤的配置值 */
    private static final String FILTER_OFF = "off";

    @Autowired
    private MediaExtensionProvider mediaExtensions;

    @Autowired
    private OpenlistConfig config;

    /** 已编译的临时目录规则，按配置原文缓存——目录遍历是热路径，不能每个条目都重编译一遍正则 */
    private volatile CompiledRules transientRules = new CompiledRules(null, List.of());

    private record CompiledRules(String raw, List<Pattern> patterns) {
    }

    /**
     * 判断文件是视频文件
     *
     * @param name
     * @return
     */
    public boolean isVideo(String name) {
        return matchesExtension(name, mediaExtensions.videoExtensions());
    }

    /**
     * 判断文件是字幕文件
     *
     * @param name
     * @return
     */
    public boolean isSrt(String name) {
        return matchesExtension(name, mediaExtensions.subtitleExtensions());
    }

    /**
     * 按扩展名匹配给定集合。集合由 {@link MediaExtensionProvider} 备好（已小写、已去点），
     * 这里只提取一次扩展名再查一次哈希——目录遍历是热路径，每个条目都要走一遍。
     */
    private boolean matchesExtension(String name, Set<String> extensions) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase();
        return extensions.contains(ext);
    }

    /**
     * 判断文件是 .strm 文件（不区分大小写）
     * 如果是 .strm 文件，应直接处理，不受最小文件大小限制
     *
     * @param name 文件名或路径
     * @return true 如果以 .strm 结尾
     */
    public boolean isStrm(String name) {
        if (name == null) {
            return false;
        }
        return name.toLowerCase().endsWith(".strm");
    }

    /**
     * 判断目录名是不是「Transmission 删种过程中产生的临时目录」，是的话同步遍历要整棵跳过。
     *
     * <p><b>Transmission 删本地数据不是就地删</b>：{@code tr_torrent_files::remove()}
     * （libtransmission/torrent-files.cc）先在同级建一个临时目录、把内容整个挪进去，
     * 再在里面删、最后删掉临时目录本身——
     * <pre>auto tmpdir = tr_pathbuf{ parent, '/', tmpdir_prefix, "__XXXXXX"sv };
     * if (!tr_sys_dir_create_temp(std::data(tmpdir), error))</pre>
     * {@code tmpdir_prefix} 是种子名，{@code __XXXXXX} 是 {@code mkdtemp} 的模板，
     * 六个 X 被替换成 {@code [A-Za-z0-9]} 里的随机字符——默认规则写 {@code .+__[0-9A-Za-z]{6}}
     * 不是估的近似值，就是照着这个模板来的。「先挪进去」那一步同时解释了为什么临时目录里
     * 还套着一层同名真目录。qBittorrent 是就地删，没有这个行为。
     *
     * <p>这个临时目录通常只活几秒，但同步任务的目录遍历只要恰好撞上一次，链路就是完整的一串坏事：
     * 当成新剧集目录 → 在网盘上建出一个同名空目录 → 给里面的视频文件提交 fs/copy →
     * 复制进行中源被删光 → AList 任务转失败 → 留下一条<b>永远重试不成功</b>的失败记录 + 一条告警。
     * 实际撞到过的目录名：{@code Star.Wars...-ADWeb__kefDJG/Star.Wars...-ADWeb}。
     *
     * <p>遍历时它和真目录没有任何其它可用区别（大小、时间、内容全都是真的，因为内容本来就是真的），
     * <b>判据只能是名字</b>。规则仍做成 sys_config 的 {@code openlist.copy.transientdirs}
     * （逗号分隔的正则，整体匹配）而不是写死：Transmission 的模板将来变了、或者链路上换了别的
     * 会留临时目录的工具，用户不必等下一个版本。填 {@code off} 关闭。
     *
     * <p>只对<b>目录</b>用。文件侧不需要——真正的临时文件（{@code .!qB}/{@code .part}）
     * 本来就过不了 {@link #isVideo} 那一关。
     */
    public boolean isTransientDir(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (Pattern pattern : transientPatterns()) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取已编译的规则；配置原文没变就直接复用缓存。
     * 单条正则写错只忽略那一条并告警，不让整份规则失效——过滤全灭是静默回到出问题前的行为。
     */
    private List<Pattern> transientPatterns() {
        String raw = config.getCopyTransientDirPatterns();
        CompiledRules cached = transientRules;
        if (raw.equals(cached.raw())) {
            return cached.patterns();
        }
        List<Pattern> compiled = new ArrayList<>();
        if (!FILTER_OFF.equalsIgnoreCase(raw)) {
            for (String expr : raw.split(",")) {
                String trimmed = expr.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    compiled.add(Pattern.compile(trimmed));
                } catch (PatternSyntaxException e) {
                    log.warn("临时目录识别规则不是合法正则，已忽略该条: {}", trimmed);
                }
            }
        }
        List<Pattern> patterns = List.copyOf(compiled);
        transientRules = new CompiledRules(raw, patterns);
        return patterns;
    }

}
