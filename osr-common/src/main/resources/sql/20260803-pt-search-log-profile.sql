-- ----------------------------
-- 20260803: 匹配日志补记候选的种子画像（幂等脚本）
--
-- 过滤规则的「历史回放」拿最近的候选标题把规则草稿重新评估一遍，看改了之后哪些会变。
-- 原先日志只存标题，体积、做种数、是否免费、是否 H&R 站点都没有，回放只能比按标题判定的维度
-- （分辨率、来源、关键词、发布组、质量标签），体积上下限、最低做种数、仅免费、规避 H&R 这几条
-- 改了等于看不出效果。从这一版起把这四项一并记下，此后产生的日志回放就是完整的。
--
-- 四列都允许 NULL：历史行没有，回放据此判定「只能按标题比」。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_search_log' AND COLUMN_NAME = 'torrent_size');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_search_log` ADD COLUMN `torrent_size` BIGINT DEFAULT NULL COMMENT ''候选体积（字节）；NULL=旧日志未记录'' AFTER `indexer_id`', 'SELECT ''Column torrent_size already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_search_log' AND COLUMN_NAME = 'seeders');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_search_log` ADD COLUMN `seeders` INT DEFAULT NULL COMMENT ''候选做种数'' AFTER `torrent_size`', 'SELECT ''Column seeders already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_search_log' AND COLUMN_NAME = 'download_factor');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_search_log` ADD COLUMN `download_factor` DECIMAL(4,2) DEFAULT NULL COMMENT ''下载量系数，0 表示免费'' AFTER `seeders`', 'SELECT ''Column download_factor already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_search_log' AND COLUMN_NAME = 'hit_and_run');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_search_log` ADD COLUMN `hit_and_run` CHAR(1) DEFAULT NULL COMMENT ''来源站点是否有 H&R 考核 0-否 1-是'' AFTER `download_factor`', 'SELECT ''Column hit_and_run already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
