package com.osr.openliststrm.rename.cleanup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.orphan.StrmSourcePathResolver;
import com.osr.openliststrm.scrape.ScrapeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 重命名产物的清理服务：删主文件（STRM / 视频副本）+ 刮削文件 + 回收空目录。
 * <p>
 * 存在的理由是把"删"这件事的三种语义分开，它们此前混成了一个只删数据库行的按钮：
 * <ul>
 *   <li>删产物——磁盘上的东西没了，记录留着，孤儿扫描仍能看见它</li>
 *   <li>删记录——数据库行没了，文件留着。这是"失忆"操作，会同时让孤儿扫描失去入口、
 *       让 {@code ScrapeService} 的兄弟计数失真、让 {@code MediaRenameProcessor#processOnce}
 *       把源文件当成没处理过而重新复制一份</li>
 *   <li>两者都删——多数场景真正想要的</li>
 * </ul>
 * <p>
 * <b>目标库里的主文件是 {@code Files.copy} 出来的副本，源文件（{@code original_path}）
 * 原封不动</b>，所以删目标副本是可逆的（重跑任务就回来）。本服务任何路径下都不碰源文件：
 * 那是网盘挂载或下载器的保种目录，删它等于毁保种。
 */
@Slf4j
@Service
public class RenameCleanupService {

    @Autowired
    private ScrapeService scrapeService;

    @Autowired
    private IRenameDetailPlusService renameDetailService;

    @Autowired
    private IOpenlistStrmPlusService openlistStrmService;

    @Autowired
    private OpenlistConfig config;

    @Autowired
    private OpenListHelper openListHelper;

    /**
     * {@link #purgeStrmSource} 的结果。
     *
     * @param fileDeleted 中间 .strm 是否真的被删掉（原本就不存在时为 false）
     * @param records     删除的 openlist_strm 生成记录数
     */
    public record StrmSourceResult(boolean fileDeleted, int records) {
        public static final StrmSourceResult EMPTY = new StrmSourceResult(false, 0);
    }

    /**
     * @param mainFiles   删除的主文件（STRM / 视频副本）数
     * @param scrapeFiles 删除的刮削文件数
     * @param dirs        回收的空目录数
     * @param records     删除的 rename_detail 行数
     */
    public record PurgeResult(int mainFiles, int scrapeFiles, int dirs, int records) {
        public static final PurgeResult EMPTY = new PurgeResult(0, 0, 0, 0);

        public PurgeResult plus(PurgeResult other) {
            return new PurgeResult(mainFiles + other.mainFiles, scrapeFiles + other.scrapeFiles,
                    dirs + other.dirs, records + other.records);
        }
    }

    /**
     * 预览一批记录的清理清单：返回磁盘上真实存在、将被删除的文件路径。
     * 只读，不做任何删除。空目录不在清单里——它们要等文件删完才知道是否变空。
     */
    public List<String> preview(List<RenameDetailPlus> details) {
        if (details == null || details.isEmpty()) {
            return List.of();
        }
        Set<Integer> batchIds = idsOf(details);
        // LinkedHashSet：同一批里的多集会解析出同一个 tvshow.nfo，去重后清单才是用户看得懂的
        Set<String> paths = new LinkedHashSet<>();
        for (RenameDetailPlus detail : details) {
            Path main = mainFileOf(detail);
            if (main != null && Files.exists(main)) {
                paths.add(main.toString());
            }
            for (Path file : scrapeService.resolveScrapeFiles(detail, ScrapeService.DeleteOptions.excluding(batchIds))) {
                if (Files.exists(file)) {
                    paths.add(file.toString());
                }
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * 清理一批记录的产物。
     * <p>
     * 顺序是硬要求：<b>先删文件、后删记录</b>。删完记录就没有 new_path/new_name 可用了，
     * 而且 {@code ScrapeService} 的兄弟判定要靠这些行还在才能算对。
     * 整批 id 一次性传给兄弟判定，避免逐条删时"前几条以为还有兄弟"的中间态。
     *
     * @param details      待清理的记录
     * @param deleteRecord 是否连 rename_detail 行一起删
     */
    public PurgeResult purge(List<RenameDetailPlus> details, boolean deleteRecord) {
        if (details == null || details.isEmpty()) {
            return PurgeResult.EMPTY;
        }
        Set<Integer> batchIds = idsOf(details);
        ScrapeService.DeleteOptions options = ScrapeService.DeleteOptions.excluding(batchIds);

        int mainFiles = 0;
        int scrapeFiles = 0;
        Set<Path> touchedDirs = new LinkedHashSet<>();

        for (RenameDetailPlus detail : details) {
            Path main = mainFileOf(detail);
            if (main != null) {
                if (deleteFile(main)) {
                    mainFiles++;
                }
                touchedDirs.add(main.getParent());
            }
            scrapeFiles += scrapeService.deleteScrapeFiles(detail, options);
        }

        int dirs = 0;
        for (Path dir : touchedDirs) {
            dirs += reclaimEmptyDirs(dir);
        }

        int records = 0;
        if (deleteRecord && !batchIds.isEmpty()) {
            records = renameDetailService.removeByIds(batchIds) ? batchIds.size() : 0;
        }

        PurgeResult result = new PurgeResult(mainFiles, scrapeFiles, dirs, records);
        log.info("清理重命名产物完成：主文件={} 刮削文件={} 空目录={} 记录={}",
                result.mainFiles(), result.scrapeFiles(), result.dirs(), result.records());
        return result;
    }

    /**
     * 重命名到新位置后清理旧位置的残留。
     * <p>
     * 这是"识别错了 → 改标题重试 → 结果落到另一部剧"场景的收口。旧实现只删旧主文件，
     * 于是旧目录里留着单集 NFO、season.nfo、tvshow.nfo 和七张剧集图——Emby 扫库会扫出
     * 一个只有元数据没有视频的鬼剧集，比留个空目录糟得多。
     * <p>
     * 新位置仍需要的共享文件必须保留：传 {@code keepDir}/{@code keepShowRoot} 给兄弟判定。
     * 此刻记录的 new_path 还是旧值、且判定会排除自己，不传的话"同剧还有没有别的记录"会
     * 答成"没有"，把新位置正要用的 tvshow.nfo 一起删掉。
     *
     * @param record  数据库里的记录（new_path/new_name 仍是旧值）
     * @param newDest 新的目标文件完整路径
     */
    public void purgeRelocated(RenameDetailPlus record, Path newDest) {
        if (record == null || newDest == null) {
            return;
        }
        Path oldFile = mainFileOf(record);
        if (oldFile == null) {
            return;
        }
        Path target = newDest.toAbsolutePath().normalize();
        if (oldFile.equals(target)) {
            return;
        }

        Path newDir = target.getParent();
        Path newShowRoot = newDir == null ? null : newDir.getParent();
        int scraped = scrapeService.deleteScrapeFiles(record, ScrapeService.DeleteOptions.keeping(newDir, newShowRoot));
        boolean mainDeleted = deleteFile(oldFile);
        int dirs = reclaimEmptyDirs(oldFile.getParent());

        if (mainDeleted || scraped > 0 || dirs > 0) {
            log.info("重命名换位，已清理旧位置 {}：主文件={} 刮削文件={} 空目录={}",
                    oldFile, mainDeleted ? 1 : 0, scraped, dirs);
        }
    }

    /**
     * 清理一个无主媒体文件（反向扫描的 local_extra）：文件本身 + 同名 NFO + 回收空目录。
     * 共享的季/剧级元数据不动——同目录下可能还有有记录的正常文件在用。
     */
    public PurgeResult purgeExtraFile(Path file) {
        if (file == null) {
            return PurgeResult.EMPTY;
        }
        Path target = file.toAbsolutePath().normalize();
        int mainFiles = deleteFile(target) ? 1 : 0;
        Path dir = target.getParent();
        int scrapeFiles = 0;
        if (dir != null) {
            scrapeFiles = deleteFile(dir.resolve(ArtifactPaths.stripExtension(target.getFileName().toString()) + ".nfo")) ? 1 : 0;
        }
        int dirs = reclaimEmptyDirs(dir);
        return new PurgeResult(mainFiles, scrapeFiles, dirs, 0);
    }

    /**
     * 清理一个只剩元数据的目录（反向扫描的 metadata_only）。
     * <p>
     * 只删白名单内的元数据文件（见 {@link ArtifactPaths#METADATA_EXTENSIONS}）。目录里出现
     * 任何白名单外的文件就整个跳过并告警——判定时那里确实只有元数据，中间又多出别的东西，
     * 说明有并发写入或用户手动放了东西，这种情况下宁可不动。
     */
    public PurgeResult purgeMetadataOnlyDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return PurgeResult.EMPTY;
        }
        Path target = dir.toAbsolutePath().normalize();
        List<Path> entries;
        try (var stream = Files.list(target)) {
            entries = stream.toList();
        } catch (IOException e) {
            log.warn("读取待清理目录失败，跳过: {}", target, e);
            return PurgeResult.EMPTY;
        }

        for (Path entry : entries) {
            if (Files.isDirectory(entry) || !ArtifactPaths.isMetadataFile(entry.getFileName().toString())) {
                log.warn("目录内出现非元数据内容，跳过清理: {}（{}）", target, entry.getFileName());
                return PurgeResult.EMPTY;
            }
        }

        int deleted = 0;
        for (Path entry : entries) {
            if (deleteFile(entry)) {
                deleted++;
            }
        }
        int dirs = reclaimEmptyDirs(target);
        return new PurgeResult(0, deleted, dirs, 0);
    }

    /**
     * 清理一条 {@code source_missing} 记录对应的<b>中间产物</b>：{@code source_folder} 里那个
     * {@code .strm}，以及它在 {@code openlist_strm} 里的生成记录。
     * <p>
     * 存在的理由是 {@link #purge} 只删目标库产物和 {@code rename_detail} 行，中间产物原样留着，
     * 于是构成一个每天一轮的复发闭环：用户清理 → 次日 02:00 的 rename 定时任务
     * （{@code OpenListStrmTask#rename} → {@code MediaRenameProcessor#processOnce}）全量重扫
     * {@code source_folder} → {@code processedKeys} 从 {@code rename_detail} 重建（那行刚被删）
     * → 中间 {@code .strm} 被当成没处理过（且 {@code .strm} 不受 {@code minFileSizeBytes} 门槛约束）
     * → 重新刮削、重新复制回目标库 → 死链复活 → 次日 06:00 孤儿扫描再报同一条。
     * 用户看到的就是"明明删了怎么又冒出来"，每轮还白烧一次 TMDb 配额。
     * <p>
     * <b>必须在 {@link #purge} 之前调用。</b>闭环成立的两个条件是「{@code rename_detail} 行没了」
     * 加「中间 {@code .strm} 还在」，先断掉后者，任何一步失败都落不进闭环；反过来的话，
     * purge 成功而本方法失败就正好把两个条件凑齐了。
     * <p>
     * <b>只动 {@code .strm}，非 {@code .strm} 一律不碰。</b>那是网盘挂载或下载器保种目录里的
     * 真实文件，删它等于毁保种（见类注释）；{@code .strm} 是 OSR 自己生成的纯派生物，
     * 删错了重跑 STRM 任务就回来。构造上 {@code source_missing} 也只可能出现在 {@code .strm} 上
     * （{@code RenameOrphanScanServiceImpl#scanForward} 对非 STRM 记录直接跳过网盘核对），
     * 这里再判一次是防御性的。
     * <p>
     * 不回收空目录：{@link #reclaimEmptyDirs} 锚定 {@code 电影}/{@code 电视剧} 那一层，
     * 而中间目录树是网盘路径的镜像、没有这个锚点，调了也是空转；况且暂存目录里留几个空目录
     * 不会被任何媒体库扫到，不值得为它放宽那道边界。
     */
    public StrmSourceResult purgeStrmSource(RenameDetailPlus detail) {
        if (detail == null || detail.getOriginalPath() == null || detail.getOriginalName() == null
                || detail.getOriginalPath().isBlank() || detail.getOriginalName().isBlank()) {
            return StrmSourceResult.EMPTY;
        }
        String originalName = detail.getOriginalName();
        if (!openListHelper.isStrm(originalName)) {
            log.debug("源文件不是 .strm，不动它: {}/{}", detail.getOriginalPath(), originalName);
            return StrmSourceResult.EMPTY;
        }
        Path strmFile = Paths.get(detail.getOriginalPath()).resolve(originalName)
                .toAbsolutePath().normalize();

        // 先读内容解析网盘路径：文件一旦删掉，就再也定位不到它对应的那条 openlist_strm 记录了
        String netdiskPath = null;
        if (Files.isRegularFile(strmFile)) {
            try {
                netdiskPath = StrmSourcePathResolver.resolve(
                        Files.readString(strmFile, StandardCharsets.UTF_8),
                        config.getOpenListUrl(),
                        "1".equals(config.getOpenListStrmEncode()));
            } catch (IOException e) {
                log.warn("读取中间 .strm 内容失败，只删文件、保留生成记录: {}", strmFile, e);
            }
        }

        boolean fileDeleted = deleteFile(strmFile);
        int records = netdiskPath == null ? 0 : removeStrmRecords(netdiskPath);
        if (fileDeleted || records > 0) {
            log.info("已清理中间产物 {}：文件={} 生成记录={}", strmFile, fileDeleted ? 1 : 0, records);
        }
        return new StrmSourceResult(fileDeleted, records);
    }

    /**
     * 按网盘路径删除 {@code openlist_strm} 的生成记录。
     * <p>
     * 这一步不能省：记录留着的话，网盘上同路径的文件<b>日后重新出现</b>（重新下载、辅种回来）时，
     * {@code StrmServiceImpl#getData} 的 {@code existingKeys} 会认定它"已成功处理过"而跳过，
     * 于是 {@code .strm} 再也不会重新生成 —— 文件明明在网盘上，最终库里却永远缺这一集，
     * 且全程没有任何失败记录可查。
     * <p>
     * 先查后删而不是直接 {@code remove(wrapper)}：{@code (strm_path, strm_file_name)} 上没有唯一约束，
     * 直接删只能拿到布尔值，日志里报不出真实条数。这是低频清理路径，多一次查询无所谓。
     */
    private int removeStrmRecords(String netdiskPath) {
        int idx = netdiskPath.lastIndexOf('/');
        String dir = idx > 0 ? netdiskPath.substring(0, idx) : "/";
        String name = idx >= 0 ? netdiskPath.substring(idx + 1) : netdiskPath;
        if (name.isBlank()) {
            return 0;
        }
        // 只用 eq（惰性），不用 select —— 后者会立刻解析实体 lambda 缓存，纯单测里必炸
        LambdaQueryWrapper<OpenlistStrmPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OpenlistStrmPlus::getStrmPath, dir)
                .eq(OpenlistStrmPlus::getStrmFileName, name);
        List<OpenlistStrmPlus> rows = openlistStrmService.list(wrapper);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Integer> ids = rows.stream().map(OpenlistStrmPlus::getStrmId)
                .filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return openlistStrmService.removeByIds(ids) ? ids.size() : 0;
    }

    /**
     * 从 {@code from} 起逐级向上删除空目录，边界是 {@link ArtifactPaths#mediaRootOf}
     * 找到的 电影/电视剧 那一层（该层本身不删）。找不到锚点时一个都不删。
     *
     * @return 实际删除的目录数
     */
    public int reclaimEmptyDirs(Path from) {
        if (from == null) {
            return 0;
        }
        Path boundary = ArtifactPaths.mediaRootOf(from);
        if (boundary == null) {
            log.debug("未找到媒体库边界（电影/电视剧），放弃回收空目录: {}", from);
            return 0;
        }
        int removed = 0;
        Path cur = from.toAbsolutePath().normalize();
        while (cur != null && cur.startsWith(boundary) && !cur.equals(boundary)) {
            if (Files.isDirectory(cur)) {
                boolean empty;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(cur)) {
                    empty = !stream.iterator().hasNext();
                } catch (IOException e) {
                    log.warn("检查目录是否为空失败，停止回收: {}", cur, e);
                    break;
                }
                if (!empty) {
                    break;
                }
                try {
                    Files.delete(cur);
                    removed++;
                    log.debug("已回收空目录: {}", cur);
                } catch (IOException e) {
                    log.warn("删除空目录失败，停止回收: {}", cur, e);
                    break;
                }
            } else if (Files.exists(cur)) {
                break; // 同名文件占位，不该发生，保守停手
            }
            // 目录不存在时继续向上：它可能已被别处删掉，父目录此刻正好变空
            cur = cur.getParent();
        }
        return removed;
    }

    /** 记录对应的主文件（STRM / 视频副本）绝对路径；失败记录没有 new_name，返回 null */
    private static Path mainFileOf(RenameDetailPlus detail) {
        if (detail == null || detail.getNewPath() == null || detail.getNewName() == null
                || detail.getNewPath().isBlank() || detail.getNewName().isBlank()) {
            return null;
        }
        return Paths.get(detail.getNewPath()).resolve(detail.getNewName()).toAbsolutePath().normalize();
    }

    private static Set<Integer> idsOf(List<RenameDetailPlus> details) {
        return details.stream().map(RenameDetailPlus::getId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean deleteFile(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", file, e);
            return false;
        }
    }
}
