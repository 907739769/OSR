package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.*;
import com.osr.openliststrm.mybatisplus.service.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 只读：只通过实际记录关联恢复流程；找不到关联不等同于执行失败。 */
@Service
public class SubscriptionWorkflowService {
    public record Step(String stage, String state, String message, String component, String recordKey, Integer recordId) {}
    public record Episode(Integer episode, List<Step> steps) {}
    private final IPtSubscriptionEpisodePlusService episodes;
    private final IPtDownloadRecordPlusService downloads;
    private final IRenameDetailPlusService renames;
    private final IOpenlistCopyPlusService copies;
    private final IOpenlistStrmPlusService strms;
    public SubscriptionWorkflowService(IPtSubscriptionEpisodePlusService episodes, IPtDownloadRecordPlusService downloads,
                                       IRenameDetailPlusService renames, IOpenlistCopyPlusService copies, IOpenlistStrmPlusService strms) {
        this.episodes = episodes; this.downloads = downloads; this.renames = renames; this.copies = copies; this.strms = strms;
    }
    public List<Episode> inspect(PtSubscriptionPlus sub, boolean admin) {
        return episodes.list(new QueryWrapper<PtSubscriptionEpisodePlus>().eq("sub_id", sub.getId())
                .in("state", "MISSING", "IN_FLIGHT", "BLOCKED").orderByAsc("episode").last("LIMIT 30"))
                .stream().filter(ep -> SubscriptionService.aired(ep, java.time.LocalDate.now())).map(ep -> inspectEpisode(sub, ep, admin)).toList();
    }
    private Episode inspectEpisode(PtSubscriptionPlus sub, PtSubscriptionEpisodePlus ep, boolean admin) {
        List<Step> steps = new ArrayList<>();
        var download = ep.getDownloadId() == null ? null : downloads.getById(ep.getDownloadId());
        if (download != null && Objects.equals(download.getSubId(), sub.getId())) {
            steps.add(new Step("下载", download.getState(), Objects.requireNonNullElse(download.getFailReason(),
                    "下载记录状态：" + download.getState()), "openlist/ptDownloadRecord/index", "recordId", download.getId()));
        } else {
            steps.add(new Step("下载", "UNKNOWN", "没有关联下载记录，可先查看匹配日志或补搜", null, null, null));
        }
        if (!admin) {
            steps.add(new Step("后续处理", "UNKNOWN", "同步、STRM 与刮削属于共享配置，请联系管理员检查", null, null, null));
            return new Episode(ep.getEpisode(), steps);
        }
        boolean movie = "MOVIE".equalsIgnoreCase(sub.getMediaType());
        var query = new QueryWrapper<RenameDetailPlus>().eq("tmdb_id", String.valueOf(sub.getTmdbId()))
                .eq("media_type", movie ? "movie" : "tv");
        if (!movie) query.apply("CAST(season AS UNSIGNED) = {0}", sub.getSeason())
                .apply("CAST(episode AS UNSIGNED) = {0}", ep.getEpisode());
        var rename = renames.getOne(query.orderByDesc("id").last("LIMIT 1"));
        if (rename != null) {
            var copy = copies.getOne(new QueryWrapper<OpenlistCopyPlus>().eq("copy_dst_path", rename.getOriginalPath())
                    .eq("copy_dst_file_name", rename.getOriginalName()).orderByDesc("copy_id").last("LIMIT 1"));
            steps.add(copy == null ? unknown("同步", "未找到精确关联的同步记录；本地导入或路径映射也可能没有此记录", "openlist/copyRecord/index") :
                    new Step("同步", "3".equals(copy.getCopyStatus()) ? "SUCCEEDED" : "1".equals(copy.getCopyStatus()) ? "RUNNING" : "FAILED",
                            Objects.requireNonNullElse(copy.getFailReason(), "同步状态：" + copy.getCopyStatus()), "openlist/copyRecord/index", "copyId", copy.getCopyId()));
            var strm = strms.getOne(new QueryWrapper<OpenlistStrmPlus>().eq("strm_path", rename.getOriginalPath())
                    .eq("strm_file_name", rename.getOriginalName()).orderByDesc("strm_id").last("LIMIT 1"));
            steps.add(strm == null ? unknown("STRM", "没有精确关联的 STRM 记录；请按网盘源路径检查生成记录", "openlist/strmRecord/index") :
                    new Step("STRM", "1".equals(strm.getStrmStatus()) ? "SUCCEEDED" : "FAILED",
                            Objects.requireNonNullElse(strm.getFailReason(), "STRM 已生成"), "openlist/strmRecord/index", "strmId", strm.getStrmId()));
            steps.add(new Step("重命名 / 刮削", "0".equals(rename.getStatus()) || "2".equals(rename.getScrapeStatus()) ? "FAILED" : "1".equals(rename.getScrapeStatus()) ? "SUCCEEDED" : "UNKNOWN",
                    Objects.requireNonNullElse(rename.getScrapeMsg(), "查看该文件的重命名与刮削结果"), "openlist/renameDetail/index", "id", rename.getId()));
        } else {
            steps.add(unknown("同步", "尚未关联到后续文件，先检查上传是否完成；无需因此重新下载", "openlist/copyRecord/index"));
            steps.add(unknown("STRM", "尚无可确认的生成记录", "openlist/strmRecord/index"));
            steps.add(unknown("重命名 / 刮削", "尚无匹配此作品及季集的记录", "openlist/renameDetail/index"));
        }
        steps.add(unknown("媒体库", "完成文件处理后可执行对账；扫描与路径映射在媒体服务器页检查", "openlist/ptMediaServer/index"));
        return new Episode(ep.getEpisode(), steps);
    }
    private Step unknown(String stage, String message, String component) { return new Step(stage, "UNKNOWN", message, component, null, null); }
}
