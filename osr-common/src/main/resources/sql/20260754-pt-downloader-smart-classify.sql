-- ----------------------------
-- 20260754: pt_downloader 增加 smart_classify_level 列，
-- 用于按媒体类型/首播年份给种子保存路径动态拼子目录（幂等脚本）。
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'smart_classify_level');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `smart_classify_level` varchar(20) NOT NULL DEFAULT ''NONE'' COMMENT ''保存路径智能分类级别：NONE-不分类 CATEGORY-按类型 CATEGORY_YEAR-按类型+首播年份'' AFTER `enabled`', 'SELECT ''Column smart_classify_level already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
