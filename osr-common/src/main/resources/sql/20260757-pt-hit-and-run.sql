-- ----------------------------
-- 20260757: H&R（Hit and Run）保种防护
--
-- 背景：OSR 自己从不删种，H&R 风险来自两处——用户在下载器里手动删，以及下载器的
-- 自动管理/做种限额在达标前把种子清掉。而 DownloadTrackService 原本只追踪
-- PUSHED/DOWNLOADING 的记录，种子一旦 COMPLETED 就彻底脱离视野，做种时长和分享率
-- 无人过问，用户既不知道哪些种子还不能删，也不知道自己什么时候已经踩了雷。
--
-- pt_indexer：H&R 是站点属性而非种子属性——Torznab 协议没有标准的 H&R 字段，
-- 索引器不会逐条告诉你哪个种子要考核，只能按站点整体判定。
-- pt_download_record：记录每条下载的保种进度与结局。
--
-- 幂等 ADD：逐列判断存在与否，写法与 20260744-pt-filter-require-chinese-subtitle.sql 一致。
-- 全部默认值等价于"该站点不考核 H&R"，因此本迁移对既有部署是行为无变化的。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_indexer' AND COLUMN_NAME = 'hr_enabled');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_indexer` ADD COLUMN `hr_enabled` CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''该站点是否有H&R考核 0-否 1-是'' AFTER `last_seen_guid_hash`', 'SELECT ''Column hr_enabled already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_indexer' AND COLUMN_NAME = 'hr_seed_hours');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_indexer` ADD COLUMN `hr_seed_hours` INT(10) NOT NULL DEFAULT 0 COMMENT ''H&R要求的最短做种时长(小时)，0表示不按时长考核'' AFTER `hr_enabled`', 'SELECT ''Column hr_seed_hours already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_indexer' AND COLUMN_NAME = 'hr_ratio');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_indexer` ADD COLUMN `hr_ratio` DOUBLE NOT NULL DEFAULT 0 COMMENT ''H&R要求的最低分享率，0表示不按分享率考核'' AFTER `hr_seed_hours`', 'SELECT ''Column hr_ratio already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'hr_state');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `hr_state` VARCHAR(16) DEFAULT NULL COMMENT ''H&R保种状态 PENDING/SATISFIED/VIOLATED，NULL表示不适用'' AFTER `completed_time`', 'SELECT ''Column hr_state already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'hr_seed_seconds');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `hr_seed_seconds` BIGINT(20) DEFAULT NULL COMMENT ''最近一次采样到的累计做种秒数'' AFTER `hr_state`', 'SELECT ''Column hr_seed_seconds already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'hr_ratio');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `hr_ratio` DOUBLE DEFAULT NULL COMMENT ''最近一次采样到的分享率'' AFTER `hr_seed_seconds`', 'SELECT ''Column hr_ratio already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'hr_satisfied_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `hr_satisfied_time` DATETIME(0) DEFAULT NULL COMMENT ''H&R达标时间'' AFTER `hr_ratio`', 'SELECT ''Column hr_satisfied_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'hr_limits_applied');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `hr_limits_applied` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否已按站点H&R规则给下载器下发分享限额'' AFTER `hr_satisfied_time`', 'SELECT ''Column hr_limits_applied already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- hr_state 上建索引：DownloadTrackService 每 30 秒按 state + hr_state 捞一次待保种记录，
-- 保种周期动辄几天，这批记录会长期驻留，不建索引会随着历史下载记录增长逐步退化成全表扫描。
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND INDEX_NAME = 'idx_hr_state');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD INDEX `idx_hr_state`(`hr_state`) USING BTREE', 'SELECT ''Index idx_hr_state already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
