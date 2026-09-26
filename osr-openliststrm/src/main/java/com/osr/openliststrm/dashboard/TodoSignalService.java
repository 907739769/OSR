package com.osr.openliststrm.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderHealthRegistry;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.task.DownloadRecordAdminService;
import com.osr.openliststrm.pt.task.DownloadRecordState;
import com.osr.openliststrm.pt.task.UnresolvedFailureSql;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 首页「待办提醒」里要后端算的几路信号。缺集体检与索引器状态前端直接读已有接口，不经过这里。
 * <p>
 * <b>三路各自兜底</b>：任一路查询出错只把那一路置为 null（前端据此说「没取到」），
 * 不让整个接口失败——待办卡片宁可少一格，也不能因为一张表查挂了就整块报错。
 *
 * @author Jack
 */
@Slf4j
@Service
public class TodoSignalService {

    private final IPtDownloaderPlusService downloaderService;
    private final IPtMediaServerPlusService mediaServerService;
    private final IPtDownloadRecordPlusService recordService;
    private final DownloaderHealthRegistry downloaderHealth;

    public TodoSignalService(IPtDownloaderPlusService downloaderService, IPtMediaServerPlusService mediaServerService,
                             IPtDownloadRecordPlusService recordService, DownloaderHealthRegistry downloaderHealth) {
        this.downloaderService = downloaderService;
        this.mediaServerService = mediaServerService;
        this.recordService = recordService;
        this.downloaderHealth = downloaderHealth;
    }

    /**
     * @param scope 下载记录的可见范围；同时决定要不要带出错误详情——下载器、媒体服务器的报错里
     *              常带着内网地址，只给管理员看
     */
    public TodoSignals signals(PtStatsScope scope) {
        return new TodoSignals(offlineDownloaders(scope.all()), unhealthyMediaServers(scope.all()),
                unresolvedFailedDownloads(scope));
    }

    private List<Problem> offlineDownloaders(boolean withDetail) {
        try {
            List<Problem> result = new ArrayList<>();
            for (PtDownloaderPlus d : downloaderService.list(new LambdaQueryWrapper<PtDownloaderPlus>()
                    .eq(PtDownloaderPlus::getEnabled, "1"))) {
                DownloaderHealthRegistry.Failure failure = downloaderHealth.offline(d.getId());
                if (failure != null) {
                    result.add(new Problem(d.getName(), failure.since(), withDetail ? failure.lastError() : null));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("首页待办：查询下载器离线状态失败：{}", e.getMessage(), e);
            return null;
        }
    }

    private List<Problem> unhealthyMediaServers(boolean withDetail) {
        try {
            return mediaServerService.list(new LambdaQueryWrapper<PtMediaServerPlus>()
                            .eq(PtMediaServerPlus::getEnabled, "1")
                            .eq(PtMediaServerPlus::getLastCheckOk, "0"))
                    .stream()
                    .map(m -> new Problem(m.getName(), m.getLastCheckTime(), withDetail ? m.getLastCheckError() : null))
                    .toList();
        } catch (Exception e) {
            log.warn("首页待办：查询媒体服务器状态失败：{}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 还没被后续推送接替、也没被用户忽略的失败下载。接替口径同下载记录页与统计面板（{@link UnresolvedFailureSql}）；
     * 忽略只在这里与下载记录页的「隐藏已忽略」里扣掉，统计面板照算。
     */
    private Long unresolvedFailedDownloads(PtStatsScope scope) {
        try {
            QueryWrapper<PtDownloadRecordPlus> wrapper = new QueryWrapper<PtDownloadRecordPlus>()
                    .eq("state", DownloadRecordState.FAILED.value())
                    .ne("fail_ignored", DownloadRecordAdminService.FAIL_IGNORED)
                    .apply(UnresolvedFailureSql.NOT_SUPERSEDED);
            String visibleSubs = scope.visibleSubIdSql();
            if (visibleSubs != null) {
                wrapper.inSql("sub_id", visibleSubs);
            }
            return recordService.count(wrapper);
        } catch (Exception e) {
            log.warn("首页待办：统计未解决的失败下载失败：{}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 待办信号。任一字段为 null 表示那一路没取到（不是「没有问题」）。
     *
     * @param offlineDownloaders       连续拉取失败的下载器
     * @param unhealthyMediaServers    最近一次访问失败的媒体服务器
     * @param unresolvedFailedDownloads 还没着落的失败下载数
     */
    public record TodoSignals(List<Problem> offlineDownloaders, List<Problem> unhealthyMediaServers,
                              Long unresolvedFailedDownloads) {
    }

    /**
     * 一个出问题的对象。
     *
     * @param name   名称
     * @param since  开始出问题的时间（媒体服务器是最近一次检查时间）
     * @param detail 错误详情，非管理员为 null
     */
    public record Problem(String name, Date since, String detail) {
    }
}
