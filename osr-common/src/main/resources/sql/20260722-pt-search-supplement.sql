-- ----------------------------
-- 20260722: 搜索补集功能 —— 订阅加自动补搜开关与上次搜索时间，全局配置加自动补搜周期（幂等脚本）
-- 沿用 20260728-pt-subscription-original-title.sql 的 INFORMATION_SCHEMA 判断写法。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'auto_search');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `auto_search` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT ''0'' COMMENT ''是否开启自动定时补搜 0-否 1-是'' AFTER `last_match_time`', 'SELECT ''Column auto_search already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'last_search_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `last_search_time` datetime(0) NULL DEFAULT NULL COMMENT ''上次发起搜索补集的时间，用于自动补搜到期判断与前端展示'' AFTER `auto_search`', 'SELECT ''Column last_search_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'auto_search_interval_hours');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `auto_search_interval_hours` int(10) NOT NULL DEFAULT 24 COMMENT ''自动补搜的全局周期(小时)'' AFTER `preferred_size`', 'SELECT ''Column auto_search_interval_hours already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
