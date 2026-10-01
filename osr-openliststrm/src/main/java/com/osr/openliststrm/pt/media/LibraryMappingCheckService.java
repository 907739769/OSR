package com.osr.openliststrm.pt.media;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmTaskPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService;
import com.osr.openliststrm.service.StrmSettingsFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置页「检查路径映射」：把 OSR 会往里写新文件的每个目录逐条映射一遍，告诉用户它落在哪个媒体库、或者不在任何库下。
 * <p>
 * 通知刷新最怕的是「配了但没生效」——库外的路径媒体服务器照样回 2xx，日志里一切正常。这里让用户
 * 保存前就能看到每个目录的结论，不必等下一集入库再去翻日志。用的是表单里<b>还没保存</b>的映射文本。
 * </p>
 */
@Service
public class LibraryMappingCheckService {

    /**
     * 一个目录的检查结果。
     *
     * @param source      这个目录是谁的（「STRM 全局输出目录」「重命名任务#2 目标目录」）
     * @param localPath   OSR 视角
     * @param mappedPath  媒体服务器视角
     * @param rule        命中的映射规则；null 表示按原路径比对
     * @param libraryName 落在哪个库；null 表示不在任何库下
     * @param libraryPath 那个库的目录
     * @param nestedLibraries 目录本身不在库下、但它<b>下面</b>有库目录时，列出那些库名——常见于把整个
     *                        STRM 输出目录挂进容器、库只建在其中的「电视剧」「电影」子目录上，这时下面的文件照样会通知到
     */
    public record Row(String source, String localPath, String mappedPath, String rule,
                      String libraryName, String libraryPath, List<String> nestedLibraries) {
    }

    /** @param libraries 媒体服务器上的全部库目录，用户核对映射右边该怎么写时要看它 */
    public record Result(List<LibraryRoot> libraries, List<Row> rows) {
    }

    private final OpenlistConfig config;
    private final IOpenlistStrmTaskPlusService strmTaskService;
    private final IRenameTaskPlusService renameTaskService;
    private final MediaServerClientFactory clientFactory;

    public LibraryMappingCheckService(OpenlistConfig config, IOpenlistStrmTaskPlusService strmTaskService,
                                      IRenameTaskPlusService renameTaskService, MediaServerClientFactory clientFactory) {
        this.config = config;
        this.strmTaskService = strmTaskService;
        this.renameTaskService = renameTaskService;
        this.clientFactory = clientFactory;
    }

    /**
     * @throws IllegalArgumentException 类型不支持通知刷新，或不支持该类型
     * @throws IOException              拉媒体库列表失败
     */
    public Result check(PtMediaServerPlus server) throws IOException {
        List<LibraryRoot> roots = clientFactory.get(server).listLibraryRoots(server);
        if (roots == null) {
            throw new IllegalArgumentException("该类型的媒体服务器不支持通知刷新");
        }
        LibraryPathMapping mapping = LibraryPathMapping.parse(server.getPathMapping());
        List<Row> rows = new ArrayList<>();
        knownDirs().forEach((dir, source) -> {
            LibraryPathMapping.Mapped mapped = mapping.map(dir);
            LibraryRoot root = LibraryPathMapping.findRoot(roots, mapped.path());
            List<String> nested = root != null ? List.of() : roots.stream()
                    .filter(r -> LibraryPathMapping.isUnder(r.path(), mapped.path()))
                    .map(LibraryRoot::name).distinct().toList();
            rows.add(new Row(source, dir, mapped.path(), mapped.rule() == null ? null : mapped.rule().describe(),
                    root == null ? null : root.name(), root == null ? null : root.path(), nested));
        });
        return new Result(roots, rows);
    }

    /** OSR 会写新文件的目录 → 来源说明；同一个目录被几处用到时只列第一处 */
    private Map<String, String> knownDirs() {
        Map<String, String> dirs = new LinkedHashMap<>();
        dirs.put(LibraryPathMapping.normalizeLocal(StrmSettingsFactory.build(config, null).outputDir()), "STRM 全局输出目录");
        for (OpenlistStrmTaskPlus task : strmTaskService.list()) {
            if (StringUtils.isBlank(task.getStrmOverride())) {
                continue;
            }
            String dir = StrmSettingsFactory.build(config, task.getStrmOverride()).outputDir();
            dirs.putIfAbsent(LibraryPathMapping.normalizeLocal(dir), "STRM 任务#" + task.getStrmTaskId() + " 输出目录");
        }
        for (RenameTaskPlus task : renameTaskService.list()) {
            if (StringUtils.isNotBlank(task.getTargetRoot())) {
                dirs.putIfAbsent(LibraryPathMapping.normalizeLocal(task.getTargetRoot()), "重命名任务#" + task.getId() + " 目标目录");
            }
        }
        return dirs;
    }
}
