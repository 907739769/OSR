-- ----------------------------
-- 20260750: pt_download_record 增加 episode_end 列，记录区间匹配（如 S01E01-E02）种子的区间结尾集号，
-- 非区间匹配（单集/季包/电影）为 NULL（幂等脚本）
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'episode_end');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `episode_end` int(11) NULL DEFAULT NULL COMMENT ''区间匹配种子的区间结尾集号，如 S01E01-E02 对应 episode=1, episode_end=2；非区间匹配为 NULL'' AFTER `episode`', 'SELECT ''Column episode_end already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
