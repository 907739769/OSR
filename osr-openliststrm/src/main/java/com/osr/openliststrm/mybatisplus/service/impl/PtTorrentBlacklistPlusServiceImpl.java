package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtTorrentBlacklistPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.task.dto.BatchBlacklistResult;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * <p>
 * PT 种子/发布组手动黑名单 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Service
public class PtTorrentBlacklistPlusServiceImpl extends ServiceImpl<PtTorrentBlacklistPlusMapper, PtTorrentBlacklistPlus>
        implements IPtTorrentBlacklistPlusService {

    private static final String DEFAULT_REASON_GUID = "从下载记录页手动拉黑该种子";
    private static final String DEFAULT_REASON_GROUP = "从下载记录页手动拉黑该发布组";

    private final IPtDownloadRecordPlusService recordService;

    /**
     * 本地标题解析器，仅用于从种子标题解析发布组。parseLocal 只做本地正则抽取，不发任何
     * 网络请求，所以传 null 客户端即可；MediaParser 不是 Spring bean（同
     * {@link com.osr.openliststrm.pt.subscription.SubscriptionEngine} 的既有写法），
     * 若通过构造器注入会导致本 Service 装配时找不到 MediaParser bean 而启动失败。
     */
    private final MediaParser mediaParser = new MediaParser(null, null);

    public PtTorrentBlacklistPlusServiceImpl(IPtDownloadRecordPlusService recordService) {
        this.recordService = recordService;
    }

    @Override
    public boolean blockRecordGuid(Integer recordId, String reason) {
        PtDownloadRecordPlus record = recordService.getById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("下载记录不存在");
        }
        String value = record.getGuidHash();
        if (existsByTypeAndValue(PtTorrentBlacklistPlus.TYPE_GUID, value)) {
            return false;
        }
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType(PtTorrentBlacklistPlus.TYPE_GUID);
        entity.setValue(value);
        entity.setDisplayValue(record.getTitle());
        entity.setReason(StringUtils.isNotBlank(reason) ? reason : DEFAULT_REASON_GUID);
        return super.save(entity);
    }

    @Override
    public boolean blockRecordReleaseGroup(Integer recordId, String reason) {
        PtDownloadRecordPlus record = recordService.getById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("下载记录不存在");
        }
        if (StringUtils.isBlank(record.getTitle())) {
            throw new IllegalArgumentException("该下载记录没有标题，无法解析发布组");
        }
        MediaInfo info = mediaParser.parseLocal(record.getTitle());
        String group = info.getReleaseGroup();
        if (StringUtils.isBlank(group)) {
            throw new IllegalArgumentException("无法从标题解析出发布组");
        }
        String normalized = group.trim().toUpperCase(Locale.ROOT);
        if (existsByTypeAndValue(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP, normalized)) {
            return false;
        }
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP);
        entity.setValue(normalized);
        entity.setDisplayValue(group);
        entity.setReason(StringUtils.isNotBlank(reason) ? reason : DEFAULT_REASON_GROUP);
        return super.save(entity);
    }

    @Override
    public BatchBlacklistResult blockRecordGuidBatch(List<Integer> recordIds, String reason) {
        return batchApply(recordIds, id -> blockRecordGuid(id, reason));
    }

    @Override
    public BatchBlacklistResult blockRecordReleaseGroupBatch(List<Integer> recordIds, String reason) {
        return batchApply(recordIds, id -> blockRecordReleaseGroup(id, reason));
    }

    /**
     * 批量拉黑的公共骨架：逐条调用单条拉黑，用 try/catch 隔离预期内的失败
     * （记录不存在、标题解析不出发布组），一条不满足条件不中断整批。
     */
    private BatchBlacklistResult batchApply(List<Integer> recordIds, Predicate<Integer> action) {
        int added = 0;
        int duplicate = 0;
        int failed = 0;
        for (Integer id : recordIds) {
            try {
                if (action.test(id)) {
                    added++;
                } else {
                    duplicate++;
                }
            } catch (IllegalArgumentException e) {
                failed++;
            }
        }
        return new BatchBlacklistResult(recordIds.size(), added, duplicate, failed);
    }

    @Override
    public boolean save(PtTorrentBlacklistPlus entity) {
        rejectGuidType(entity);
        normalizeReleaseGroupValue(entity);
        return super.save(entity);
    }

    @Override
    public boolean updateById(PtTorrentBlacklistPlus entity) {
        rejectGuidType(entity);
        normalizeReleaseGroupValue(entity);
        return super.updateById(entity);
    }

    private void rejectGuidType(PtTorrentBlacklistPlus entity) {
        if (PtTorrentBlacklistPlus.TYPE_GUID.equals(entity.getType())) {
            throw new IllegalArgumentException("管理页不支持手动新增/编辑 GUID 类型的黑名单规则，请通过下载记录页的拉黑按钮操作");
        }
    }

    /** RELEASE_GROUP 的 value 落库前归一化为去空白+大写；displayValue 为空时回填原始输入，方便管理页展示 */
    private void normalizeReleaseGroupValue(PtTorrentBlacklistPlus entity) {
        if (PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP.equals(entity.getType()) && StringUtils.isNotBlank(entity.getValue())) {
            String raw = entity.getValue().trim();
            if (StringUtils.isBlank(entity.getDisplayValue())) {
                entity.setDisplayValue(raw);
            }
            entity.setValue(raw.toUpperCase(Locale.ROOT));
        }
    }

    private boolean existsByTypeAndValue(String type, String value) {
        Long count = getBaseMapper().selectCount(new LambdaQueryWrapper<PtTorrentBlacklistPlus>()
                .eq(PtTorrentBlacklistPlus::getType, type)
                .eq(PtTorrentBlacklistPlus::getValue, value));
        return count != null && count > 0;
    }
}
