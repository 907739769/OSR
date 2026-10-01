package com.osr.openliststrm.pt.media;

import com.osr.common.utils.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 「OSR 里的路径 → 媒体服务器看到的路径」的映射，以及「这个路径落在哪个媒体库下」的判定。纯逻辑，不碰 I/O。
 * <p>
 * 配置文本每行一条 {@code OSR路径 => 媒体服务器路径}，空行和 {@code #} 开头的行忽略。匹配取<b>最长前缀</b>，
 * 且必须落在路径分隔符上：{@code /data/media} 覆盖 {@code /data/media/电视剧}，不覆盖 {@code /data/media2}
 * （与 {@code StrmServiceImpl#pickCoveringTask} 同一个坑）。没有规则命中时按原路径比对，
 * 于是两边挂载路径一致的部署什么都不用配。
 * </p>
 * <p>
 * 媒体服务器可能跑在 Windows 上（{@code D:\Media}、{@code \\nas\share}）：映射目标是 Windows 路径时，
 * 余下部分的 {@code /} 一并换成 {@code \}；判定「在不在库下」时 Windows 路径不分大小写。
 * </p>
 */
public final class LibraryPathMapping {

    public static final String ARROW = "=>";

    /** 一条映射规则，from 是 OSR 侧（已规范化），to 是媒体服务器侧（原样） */
    public record Rule(String from, String to) {
        public String describe() {
            return (from.isEmpty() ? "/" : from) + " " + ARROW + " " + to;
        }
    }

    /**
     * 映射结果。
     *
     * @param rule 命中的规则；null 表示没有规则命中、按原路径比对
     */
    public record Mapped(String path, Rule rule) {
    }

    private static final LibraryPathMapping EMPTY = new LibraryPathMapping(List.of());

    /** 按 from 长度降序，第一条命中的即最长前缀 */
    private final List<Rule> rules;

    private LibraryPathMapping(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * 解析配置文本。格式不对的行跳过——保存时已由 {@link #validate} 拦过，这里不再为它报错，
     * 免得一行历史脏数据让整台服务器的通知都停掉。
     */
    public static LibraryPathMapping parse(String text) {
        if (StringUtils.isBlank(text)) {
            return EMPTY;
        }
        List<Rule> rules = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int arrow = line.indexOf(ARROW);
            if (arrow < 0) {
                continue;
            }
            String from = line.substring(0, arrow).trim();
            String to = line.substring(arrow + ARROW.length()).trim();
            if (from.isEmpty() || to.isEmpty()) {
                continue;
            }
            rules.add(new Rule(normalizeLocal(from), to));
        }
        rules.sort(Comparator.comparingInt((Rule r) -> r.from().length()).reversed());
        return new LibraryPathMapping(List.copyOf(rules));
    }

    /**
     * 保存前的校验。
     *
     * @return 错误文案；没问题返回 null
     */
    public static String validate(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int arrow = line.indexOf(ARROW);
            String from = arrow < 0 ? "" : line.substring(0, arrow).trim();
            String to = arrow < 0 ? "" : line.substring(arrow + ARROW.length()).trim();
            if (from.isEmpty() || to.isEmpty()) {
                return "路径映射第 " + (i + 1) + " 行格式不对，应为「OSR路径 => 媒体服务器路径」：" + line;
            }
            // OSR 跑在 Linux 容器里，左边只可能是 / 开头的绝对路径；写反了两边是最常见的错误
            if (!from.startsWith("/")) {
                return "路径映射第 " + (i + 1) + " 行左边应是 OSR 容器里的绝对路径（以 / 开头），"
                        + "右边才是媒体服务器上的路径：" + line;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public List<Rule> rules() {
        return rules;
    }

    /** 把 OSR 里的路径映射成媒体服务器视角的路径 */
    public Mapped map(String localPath) {
        String local = normalizeLocal(localPath);
        for (Rule rule : rules) {
            String from = rule.from();
            if (local.equals(from) || local.startsWith(from + "/")) {
                String rest = local.substring(from.length());
                String to = stripTrailingSeparators(rule.to());
                if (isWindowsPath(rule.to())) {
                    rest = rest.replace('/', '\\');
                }
                return new Mapped(to + rest, rule);
            }
        }
        return new Mapped(local.isEmpty() ? "/" : local, null);
    }

    /**
     * 找出 {@code serverPath} 所在的媒体库目录；几个库目录互相嵌套时取最深的那个。
     *
     * @return 不在任何库目录下时返回 null
     */
    public static LibraryRoot findRoot(List<LibraryRoot> roots, String serverPath) {
        LibraryRoot best = null;
        int bestLen = -1;
        for (LibraryRoot root : roots) {
            if (StringUtils.isBlank(root.path()) || !isUnder(serverPath, root.path())) {
                continue;
            }
            int len = canonical(root.path()).length();
            if (len > bestLen) {
                best = root;
                bestLen = len;
            }
        }
        return best;
    }

    /** {@code child} 是否等于 {@code parent} 或在它下面（媒体服务器视角的路径） */
    public static boolean isUnder(String child, String parent) {
        String c = canonical(child);
        String p = canonical(parent);
        return c.equals(p) || c.startsWith(p + "/");
    }

    /** 比较用的规范形式：Windows 路径统一成 / 并转小写，末尾分隔符去掉，根目录归一成空串 */
    private static String canonical(String path) {
        String value = path == null ? "" : path.trim();
        if (isWindowsPath(value)) {
            value = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** OSR 侧路径的规范形式：统一成 /、去掉末尾分隔符，根目录归一成空串（于是它覆盖一切绝对路径） */
    static String normalizeLocal(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String stripTrailingSeparators(String path) {
        String value = path;
        while (value.endsWith("/") || value.endsWith("\\")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 盘符开头（D:）或含反斜杠（UNC、D:\）都按 Windows 路径处理 */
    static boolean isWindowsPath(String path) {
        if (path == null) {
            return false;
        }
        return path.indexOf('\\') >= 0 || (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':');
    }
}
