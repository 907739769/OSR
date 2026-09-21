-- ----------------------------
-- 20260800: 洗版扫描的搜索预算与退避（幂等脚本）
--
-- 原先「同时在途洗版数」只限推送数、不限搜索次数：找不到更好版本的集不占名额，
-- 于是每个扫描周期都会把全部 PENDING 集挨个对所有索引器搜一遍——几百集的库就是
-- 每 6 小时几百次全站搜索，而这些集里的绝大多数这一轮、下一轮都不会有更好的版本。
--
-- 1. pt_upgrade_config.max_searches_per_round：每轮最多发起的洗版搜索次数
-- 2. pt_subscription_episode.upgrade_searched_at：上次为这一集发起洗版搜索的时间，
--    扫描按它升序轮转（NULL 排最前），名额有限时不会永远只搜排在前面的那几集
-- 3. pt_subscription_episode.upgrade_miss_count：连续没搜到更好版本的次数，
--    按它做指数退避（扫描周期 × 2^n，封顶 7 天）
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_upgrade_config' AND COLUMN_NAME = 'max_searches_per_round');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_upgrade_config` ADD COLUMN `max_searches_per_round` INT NOT NULL DEFAULT 20 COMMENT ''每轮最多发起的洗版搜索次数'' AFTER `max_concurrent`', 'SELECT ''Column max_searches_per_round already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription_episode' AND COLUMN_NAME = 'upgrade_searched_at');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription_episode` ADD COLUMN `upgrade_searched_at` DATETIME DEFAULT NULL COMMENT ''上次洗版搜索时间；NULL=还没搜过'' AFTER `upgrade_state`', 'SELECT ''Column upgrade_searched_at already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription_episode' AND COLUMN_NAME = 'upgrade_miss_count');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription_episode` ADD COLUMN `upgrade_miss_count` INT NOT NULL DEFAULT 0 COMMENT ''连续没搜到更好版本的次数，用于退避'' AFTER `upgrade_searched_at`', 'SELECT ''Column upgrade_miss_count already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
