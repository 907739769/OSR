-- 下载和人工确认的作品身份独立于自动识别结果，失败重试仍保留绑定。
CREATE TABLE IF NOT EXISTS `media_identity_binding` (
  `binding_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_kind` varchar(16) NOT NULL,
  `download_id` int DEFAULT NULL,
  `source_path` text NOT NULL,
  `relative_path` text DEFAULT NULL,
  `file_name_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `file_size` bigint DEFAULT NULL,
  `tmdb_id` varchar(16) NOT NULL,
  `media_type` varchar(8) NOT NULL,
  `season` varchar(8) DEFAULT NULL,
  `episode` varchar(8) DEFAULT NULL,
  PRIMARY KEY (`binding_key`),
  KEY `idx_identity_file` (`file_name_hash`, `file_size`),
  KEY `idx_identity_download` (`download_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='下载与人工确认的文件作品身份';

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rename_detail' AND COLUMN_NAME = 'fail_reason');
SET @sql := IF(@exist = 0, 'ALTER TABLE `rename_detail` ADD COLUMN `fail_reason` varchar(512) DEFAULT NULL COMMENT ''重命名失败原因''', 'SELECT ''Column fail_reason already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
