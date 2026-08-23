package com.osr.openliststrm.rename;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.rename.cleanup.RenameCleanupService;
import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 重命名事件监听器实现类
 *
 * @author: Jack
 * @creat: 2026/1/13 11:13
 */
@Slf4j
@Component
public class RenameEventListenerFactoryImpl implements RenameEventListenerFactory {

    @Autowired
    private IRenameDetailPlusService renameDetailService;

    @Autowired
    private RenameCleanupService cleanupService;

    @Override
    public RenameEventListener create(final Integer taskId) {
        return new RenameEventListener() {

            @Override
            public Integer onRename(Path original, Path dest, MediaInfo info, String mediaType) {
                return persistSuccess(taskId, original, dest, info, mediaType);
            }

            @Override
            public void onRenameFailed(Path original, Path targetRoot, MediaInfo info, String mediaType, String reason) {
                persistFailed(taskId, original, targetRoot, info, mediaType, reason);
            }
        };
    }

    private Integer persistSuccess(Integer taskId, Path original, Path dest,
                                MediaInfo info, String mediaType) {
        try {
            String originalDir = original != null && original.getParent() != null
                    ? original.getParent().toString()
                    : null;
            String originalName = original != null ? original.getFileName().toString() : null;

            String destDir = dest != null && dest.getParent() != null
                    ? dest.getParent().toString()
                    : null;
            String destName = dest != null ? dest.getFileName().toString() : null;

            RenameDetailPlus record = findByOriginal(originalDir, originalName);

            if (record != null) {
                // 必须在改写 record 的 new_path/new_name 之前清旧位置：
                // 清理要用旧值定位文件，也要靠这一行还指着旧路径来做兄弟判定
                cleanupService.purgeRelocated(record, dest);
            } else {
                record = new RenameDetailPlus();
                record.setOriginalPath(originalDir);
                record.setOriginalName(originalName);
            }

            fillCommonFields(record, info, mediaType);
            record.setNewPath(destDir);
            record.setNewName(destName);
            record.setStatus("1");

            saveOrUpdate(record);
            log.debug("重命名成功已落库：task={} {} -> {}", taskId, original, dest);
            return record.getId();
        } catch (Exception e) {
            log.error("重命名成功记录落库失败", e);
        }
        return null;
    }

    private void persistFailed(Integer taskId, Path original, Path targetRoot,
                               MediaInfo info, String mediaType, String reason) {
        try {
            String originalDir = original != null && original.getParent() != null
                    ? original.getParent().toString()
                    : null;
            String originalName = original != null ? original.getFileName().toString() : null;

            RenameDetailPlus record = findByOriginal(originalDir, originalName);
            if (record == null) {
                record = new RenameDetailPlus();
                record.setOriginalPath(originalDir);
                record.setOriginalName(originalName);
            }

            fillCommonFields(record, info, mediaType);
            record.setNewPath(targetRoot.toString());
            record.setNewName(null);
            record.setStatus("0");

            saveOrUpdate(record);
            log.info("重命名失败已落库：task={} {} reason={}", taskId, original, reason);
        } catch (Exception e) {
            log.error("重命名失败记录落库失败", e);
        }
    }

    private RenameDetailPlus findByOriginal(String path, String name) {
        if (StringUtils.isBlank(path) || StringUtils.isBlank(name)) return null;
        QueryWrapper<RenameDetailPlus> qw = new QueryWrapper<>();
        qw.eq("original_path", path).eq("original_name", name);
        List<RenameDetailPlus> list = renameDetailService.list(qw);
        return list.isEmpty() ? null : list.get(0);
    }

    private void fillCommonFields(RenameDetailPlus record, MediaInfo info, String mediaType) {
        record.setMediaType(mediaType);
        if (info == null) return;
        record.setTitle(info.getTitle());
        record.setYear(info.getYear());
        record.setSeason(info.getSeason());
        record.setEpisode(info.getEpisode());
        record.setTmdbId(info.getTmdbId());
        record.setResolution(info.getResolution());
        record.setVideoCodec(info.getVideoCodec());
        record.setAudioCodec(info.getAudioCodec());
        record.setSource(info.getSource());
        record.setReleaseGroup(info.getReleaseGroup());
    }

    private void saveOrUpdate(RenameDetailPlus record) {
        if (record.getId() == null) {
            renameDetailService.save(record);
        } else {
            renameDetailService.updateById(record);
        }
    }
}