package com.osr.openliststrm.rename;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.rename.model.MediaInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                deleteOldFiles(record, destDir, destName);
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
            log.debug("Persist rename success task={} {} -> {}", taskId, original, dest);
            return record.getId();
        } catch (Exception e) {
            log.error("Persist rename success failed", e);
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
            log.info("Persist rename failed task={} {} reason={}", taskId, original, reason);
        } catch (Exception e) {
            log.error("Persist rename failed error", e);
        }
    }

    private RenameDetailPlus findByOriginal(String path, String name) {
        if (StringUtils.isBlank(path) || StringUtils.isBlank(name)) return null;
        QueryWrapper<RenameDetailPlus> qw = new QueryWrapper<>();
        qw.eq("original_path", path).eq("original_name", name);
        List<RenameDetailPlus> list = renameDetailService.list(qw);
        return list.isEmpty() ? null : list.get(0);
    }

    private void deleteOldFiles(RenameDetailPlus record, String newDir, String newName) {
        try {
            if (StringUtils.isBlank(record.getNewPath()) || StringUtils.isBlank(record.getNewName())) return;
            Path oldPath = Paths.get(record.getNewPath()).resolve(record.getNewName());
            Path newPath = Paths.get(newDir).resolve(newName);
            if (Files.exists(oldPath) && !oldPath.normalize().equals(newPath.normalize())) {
                Files.deleteIfExists(oldPath);
            }
        } catch (IOException e) {
            log.warn("Delete old file failed", e);
        }
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