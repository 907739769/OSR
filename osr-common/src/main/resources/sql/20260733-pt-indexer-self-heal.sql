-- ----------------------------
-- 20260733: pt_indexer 增加 disabled_at 列，用于自动停用后的冷却计时（幂等脚本）
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_indexer' AND COLUMN_NAME = 'disabled_at');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_indexer` ADD COLUMN `disabled_at` datetime NULL DEFAULT NULL COMMENT ''自动停用/最近一次自愈探测失败的时间，用于冷却期计时'' AFTER `fail_count`', 'SELECT ''Column disabled_at already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
