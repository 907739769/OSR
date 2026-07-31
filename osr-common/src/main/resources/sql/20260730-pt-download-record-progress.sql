-- ----------------------------
-- 20260730: pt_download_record 增加 progress 列，用于前端展示下载进度（幂等脚本）
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建，做法同 20260729-pt-subscription-imdb-id.sql。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'progress');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `progress` decimal(5,4) NULL DEFAULT 0 COMMENT ''下载进度0~1，仅DOWNLOADING/COMPLETED状态有意义'' AFTER `state`', 'SELECT ''Column progress already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
