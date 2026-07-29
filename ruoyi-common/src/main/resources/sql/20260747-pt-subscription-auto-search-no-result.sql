-- ----------------------------
-- 20260747: pt_subscription 增加 last_auto_search_no_result 列（幂等脚本）
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- 背景：定期自动补搜现在会真正尝试补散集（见 SearchSupplementService.searchAndPushMissing），
-- 长期缺集的订阅每轮（默认24小时）都可能落空，需要记录"上一轮是否已落空"，
-- 只在从有命中/首次变为落空时通知一次，避免每轮都发 TG 通知打扰用户。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'last_auto_search_no_result');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `last_auto_search_no_result` char(1) NOT NULL DEFAULT ''0'' COMMENT ''上一轮定期自动补搜是否落空 0-否(有命中或未跑过) 1-是，用于通知去重'' AFTER `last_search_time`', 'SELECT ''Column last_auto_search_no_result already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
