-- ----------------------------
-- 20260742: pt_downloader 增加 max_concurrent 列，用于单个下载器的并发上限保护（幂等脚本）
-- 同时给 pt_download_record 补一个 (downloader_id, state) 复合索引，
-- 避免 SubscriptionEngine.loadDownloaderLoadCounts() 的按下载器+状态过滤查询退化成全表扫描。
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'max_concurrent');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `max_concurrent` int NOT NULL DEFAULT 0 COMMENT ''同时处于PUSHED/DOWNLOADING状态的最大记录数，0表示不限'' AFTER `tag`', 'SELECT ''Column max_concurrent already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND INDEX_NAME = 'idx_downloader_state');
SET @idx_sql := IF(@idx_exist = 0, 'ALTER TABLE `pt_download_record` ADD INDEX `idx_downloader_state` (`downloader_id`, `state`)', 'SELECT ''Index idx_downloader_state already exists''');
PREPARE stmt2 FROM @idx_sql;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
