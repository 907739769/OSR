package com.osr.openliststrm.mybatisplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osr.openliststrm.mybatisplus.domain.MediaIdentityBindingPlus;
import org.apache.ibatis.annotations.Insert;

public interface MediaIdentityBindingPlusMapper extends BaseMapper<MediaIdentityBindingPlus> {
    @Insert("""
            INSERT INTO media_identity_binding
              (binding_key, source_kind, download_id, download_version, source_path, relative_path, file_name_hash,
               file_size, tmdb_id, media_type, season, episode)
            VALUES (#{bindingKey}, #{sourceKind}, #{downloadId}, #{downloadVersion}, #{sourcePath}, #{relativePath}, #{fileNameHash},
                    #{fileSize}, #{tmdbId}, #{mediaType}, #{season}, #{episode})
            ON DUPLICATE KEY UPDATE source_path=VALUES(source_path), file_size=VALUES(file_size),
              tmdb_id=VALUES(tmdb_id), media_type=VALUES(media_type), season=VALUES(season), episode=VALUES(episode),
              download_version=VALUES(download_version)
            """)
    void upsert(MediaIdentityBindingPlus binding);
}
