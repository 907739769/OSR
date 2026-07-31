-- ----------------------------
-- 20260734: pt_subscription_episode 增加 fail_count 列，用于失败重试熔断计数（幂等脚本）
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription_episode' AND COLUMN_NAME = 'fail_count');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription_episode` ADD COLUMN `fail_count` int NOT NULL DEFAULT 0 COMMENT ''连续失败次数，达到阈值后状态转 BLOCKED 停止自动重试，成功入库前不清零'' AFTER `state`', 'SELECT ''Column fail_count already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
