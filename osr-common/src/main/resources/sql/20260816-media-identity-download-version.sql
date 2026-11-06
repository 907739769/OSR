-- 下载 ID 可被失败重试复用；绑定需要区分当前推送批次，并保留完整快照标记。
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'media_identity_binding' AND COLUMN_NAME = 'download_version');
SET @sql := IF(@exist = 0, 'ALTER TABLE `media_identity_binding` ADD COLUMN `download_version` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL AFTER `download_id`', 'SELECT ''Column download_version already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
