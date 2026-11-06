package com.osr.openliststrm.setup;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.*;
import com.osr.openliststrm.mybatisplus.service.*;
import com.osr.system.service.ISysConfigService;
import org.springframework.stereotype.Service;
import java.util.*;

/** 配置清单只返回是否已配，不返回任何凭据；不会在刷新清单时访问外部服务。 */
@Service
public class SetupGuideService {
    public record Step(String key, String title, boolean configured, boolean required, String component, String hint) {}
    public record Evidence(String stage, String state, String message, String component, String recordKey, Integer recordId) {}
    private final ISysConfigService config;
    private final IOpenlistStrmTaskPlusService strmTasks;
    private final IOpenlistCopyTaskPlusService copyTasks;
    private final IRenameTaskPlusService renameTasks;
    private final IPtIndexerPlusService indexers;
    private final IPtDownloaderPlusService downloaders;
    private final IPtMediaServerPlusService media;
    private final IOpenlistCopyPlusService copies;
    private final IOpenlistStrmPlusService strms;
    private final IRenameDetailPlusService renames;
    public SetupGuideService(ISysConfigService config, IOpenlistStrmTaskPlusService strmTasks,
                              IOpenlistCopyTaskPlusService copyTasks, IRenameTaskPlusService renameTasks,
                              IPtIndexerPlusService indexers, IPtDownloaderPlusService downloaders,
                              IPtMediaServerPlusService media, IOpenlistCopyPlusService copies,
                              IOpenlistStrmPlusService strms, IRenameDetailPlusService renames) {
        this.config=config; this.strmTasks=strmTasks; this.copyTasks=copyTasks; this.renameTasks=renameTasks;
        this.indexers=indexers; this.downloaders=downloaders; this.media=media; this.copies=copies; this.strms=strms; this.renames=renames;
    }
    public List<Step> status(String mode) {
        boolean pt = "PT".equals(mode);
        if (!pt && !"STRM".equals(mode)) throw new IllegalArgumentException("请选择 STRM 或 PT 场景");
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("openlist", "连接 OpenList", present("openlist.server.url") && present("openlist.server.token"), true,
                "system/config/index", "填写服务地址和 Token，然后在任务中浏览网盘目录确认连接。"));
        steps.add(new Step("strm", "配置 STRM 源目录", strmTasks.count(new QueryWrapper<OpenlistStrmTaskPlus>().eq("strm_task_status", "1")) > 0, true,
                "openlist/strmTask/index", "选择网盘目录与输出目录，先手动执行一个小目录，确认生成文件。"));
        if (pt) {
            steps.add(new Step("indexer", "连接索引器", indexers.count(new QueryWrapper<PtIndexerPlus>().eq("enabled", "1")) > 0, true,
                    "openlist/ptIndexer/index", "新增 Jackett / Prowlarr，在配置弹窗中测试连接。"));
            steps.add(new Step("downloader", "连接下载器", downloaders.count(new QueryWrapper<PtDownloaderPlus>().eq("enabled", "1").and(w -> w.isNull("role").or().ne("role", "SEED_ONLY"))) > 0, true,
                    "openlist/ptDownloader/index", "测试连接，并检查保存路径是否被同步任务监听；仅做种下载器不接订阅。"));
            steps.add(new Step("copy", "打通下载到网盘的同步", copyTasks.count(new QueryWrapper<OpenlistCopyTaskPlus>().eq("copy_task_status", "1")) > 0, true,
                    "openlist/copyTask/index", "将下载目录映射到监听目录，目标网盘目录应被 STRM 任务覆盖。"));
        }
        steps.add(new Step("tmdb", "配置 TMDb", present("openlist.tmdb.apikey"), pt,
                "system/config/index", "用于识别作品与刮削；只生成原名 STRM 时可选。"));
        steps.add(new Step("rename", "配置重命名与刮削", renameTasks.count(new QueryWrapper<RenameTaskPlus>().eq("status", "1")) > 0, false,
                "openlist/renameTask/index", "源目录选 STRM 输出位置，检查分类与刮削选项；保留原名时可跳过。"));
        steps.add(new Step("media", "连接媒体服务器", media.count(new QueryWrapper<PtMediaServerPlus>().eq("enabled", "1")) > 0, pt,
                "openlist/ptMediaServer/index", "测试连接，再逐条检查 OSR 路径到媒体库路径的映射。"));
        return steps;
    }
    private boolean present(String key) { String value=config.selectConfigByKey(key); return value != null && !value.isBlank() && !"xxx".equals(value.trim()) && !"http://xxx:5244".equals(value.trim()); }
    public List<Evidence> verify(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255 || fileName.contains("/") || fileName.contains("\\"))
            throw new IllegalArgumentException("请输入不含目录的样例文件名（最多 255 字符）");
        String name=fileName.trim();
        List<Evidence> result=new ArrayList<>();
        var copy=copies.getOne(new QueryWrapper<OpenlistCopyPlus>().eq("copy_src_file_name",name).orderByDesc("copy_id").last("LIMIT 1"));
        result.add(copy==null ? missing("同步", "openlist/copyRecord/index") : new Evidence("同步", "3".equals(copy.getCopyStatus())?"SUCCEEDED":"1".equals(copy.getCopyStatus())?"RUNNING":"FAILED",
                "最近同名源文件的同步状态："+copy.getCopyStatus(), "openlist/copyRecord/index", "copyId",copy.getCopyId()));
        var strm=strms.getOne(new QueryWrapper<OpenlistStrmPlus>().eq("strm_file_name",name).orderByDesc("strm_id").last("LIMIT 1"));
        result.add(strm==null?missing("STRM", "openlist/strmRecord/index"):new Evidence("STRM", "1".equals(strm.getStrmStatus())?"SUCCEEDED":"FAILED",
                Objects.requireNonNullElse(strm.getFailReason(),"已生成 STRM，点击记录核对源路径"), "openlist/strmRecord/index", "strmId",strm.getStrmId()));
        String strmName=name.lastIndexOf('.')>0?name.substring(0,name.lastIndexOf('.'))+".strm":name+".strm";
        var rename=renames.getOne(new QueryWrapper<RenameDetailPlus>().in("original_name",List.of(name,strmName)).orderByDesc("id").last("LIMIT 1"));
        result.add(rename==null?missing("重命名 / 刮削","openlist/renameDetail/index"):new Evidence("重命名 / 刮削",
                "0".equals(rename.getStatus())||"2".equals(rename.getScrapeStatus())?"FAILED":"1".equals(rename.getScrapeStatus())?"SUCCEEDED":"PENDING",
                Objects.requireNonNullElse(rename.getScrapeMsg(),"查看重命名与刮削结果"),"openlist/renameDetail/index","id",rename.getId()));
        return result;
    }
    private Evidence missing(String stage,String component) {return new Evidence(stage,"UNKNOWN","未找到同名记录；请先执行任务或核对文件名",component,null,null);}
}
