package com.osr.openliststrm.rename.cleanup;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 重命名产物的路径规则：媒体库边界、刮削产物文件名、元数据扩展名判定。
 * <p>
 * 纯逻辑无 I/O（同 {@link com.osr.openliststrm.orphan.OrphanReconciler} 的取向），
 * 真正的删除动作在 {@link RenameCleanupService}。把"哪些文件算产物""能删到哪一层"
 * 这两件事收在一处，是因为它们同时被清理、反向扫描、重命名换位三条链路使用，
 * 分叉一次就会出现"扫描说是残骸、清理却不认"的说不清状态。
 */
public final class ArtifactPaths {

    private ArtifactPaths() {
    }

    /**
     * 目标库的顶层目录名。{@code MediaRenameProcessor#buildDestPath} 硬编码只会产出这两种，
     * 因此它一定出现在每条重命名产物的路径里，可以拿来当空目录回收的边界锚点。
     */
    public static final Set<String> MEDIA_TOP_LEVELS = Set.of("电影", "电视剧");

    /** 剧集根目录 / 电影目录下的共享图片，文件名固定（见 MediaImageDownloader） */
    public static final List<String> SHARED_IMAGES = List.of(
            "poster.jpg", "fanart.jpg", "clearlogo.png",
            "banner.jpg", "clearart.png", "landscape.jpg", "thumb.jpg");

    /**
     * 判定"这个目录只剩元数据"时认可的扩展名。
     * 只有目录里全部文件都落在这个集合内，才会被判成 metadata_only 残骸并允许清理——
     * 出现任何集合外的文件（.txt/.torrent/用户自己放的东西）一律保守跳过。
     */
    public static final Set<String> METADATA_EXTENSIONS = Set.of(
            "nfo", "jpg", "jpeg", "png", "webp", "bmp",
            "srt", "ass", "ssa", "sub", "idx", "vtt");

    /**
     * 从产物路径向上找到 电影/电视剧 那一层，返回含该层在内的路径。
     * 空目录回收以它为下界：这一层及其祖先永远不删——它们是媒体库骨架，
     * 删掉之后 Emby/Jellyfin 的媒体库根目录会直接失效，代价远大于留一个空目录。
     *
     * @return 找不到锚点时返回 null，调用方应当放弃回收而不是自行猜一个边界
     */
    public static Path mediaRootOf(Path path) {
        if (path == null) {
            return null;
        }
        Path p = path.toAbsolutePath().normalize();
        for (int i = p.getNameCount() - 1; i >= 0; i--) {
            if (MEDIA_TOP_LEVELS.contains(p.getName(i).toString())) {
                Path sub = p.subpath(0, i + 1);
                Path root = p.getRoot();
                return root == null ? sub : root.resolve(sub);
            }
        }
        return null;
    }

    /** 文件名是否属于元数据（NFO / 图片 / 字幕） */
    public static boolean isMetadataFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return METADATA_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase());
    }

    /** 去掉扩展名，用于由媒体文件名推导同名 NFO */
    public static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 转义 MySQL LIKE 通配符。MyBatis-Plus 的 likeRight 不支持 ESCAPE 子句，
     * 但 MySQL 默认转义符就是反斜杠，直接在值里转义即可生效。
     * <p>
     * 路径里出现 {@code _} 是常态（发布组命名），不转义的话
     * {@code /电视剧/纪录片} 会匹配上 {@code /电视剧/纪录x片}，
     * "同剧是否还有别的记录"这个判据会给出错误答案。
     */
    public static String escapeLike(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
