-- ----------------------------
-- 20260749: pt_download_record 增加 files_selected 列，标记季包/区间匹配的种子是否已按目标集数
-- 完成过一次文件级过滤（排除非目标集文件），避免 DownloadTrackTask 每 30 秒重复调用下载器 API（幂等脚本）
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'files_selected');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `files_selected` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否已完成文件级过滤（排除非目标集数的文件）'' AFTER `progress`', 'SELECT ''Column files_selected already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
