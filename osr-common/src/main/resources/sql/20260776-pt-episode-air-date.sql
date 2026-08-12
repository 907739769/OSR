-- ----------------------------
-- 20260776: 每集播出日期 + 追剧日历菜单
--
-- pt_subscription_episode 此前只有集号与状态，没有播出日期——TMDb 的季端点
-- (/tv/{id}/season/{n}) 有 air_date，但订阅链路只取了 seasons[].episode_count。
-- 日历要按日期范围查，落库后是一次 SQL；不落库就得在渲染时对每个订阅打一次 TMDb。
--
-- 填充路径有三条：建订阅时、补齐新集时、以及 EpisodeAirDateSyncTask 每 12 小时
-- 对活跃订阅重同步一次。第三条同时承担存量数据的回填——播出日期本来也会变
-- （改档、提前放送），一次性回填脚本解决不了，本来就需要定期同步。
--
-- air_date 允许为 NULL：未定档的剧、TMDb 没录入的集、以及尚未同步到的存量行都是 NULL，
-- 日历按「有日期的才排进格子」处理，不猜。电影(episode=0)同样走这个字段存上映日期。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'pt_subscription_episode'
                 AND COLUMN_NAME = 'air_date');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_subscription_episode` ADD COLUMN `air_date` date NULL DEFAULT NULL COMMENT ''播出日期(TMDb air_date)，NULL=未定档或尚未同步'' AFTER `state`',
    'SELECT ''Column air_date already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 日历按日期区间扫全表，没有索引会随订阅量线性劣化
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'pt_subscription_episode'
                 AND INDEX_NAME = 'idx_episode_air_date');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_subscription_episode` ADD INDEX `idx_episode_air_date` (`air_date`)',
    'SELECT ''Index idx_episode_air_date already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 追剧日历菜单，挂在 PT下载管理(2070) 下，排在最后
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2076, '追剧日历', 2070, 11, '/openlist/ptCalendar', '', 'C', '0', '1', 'openliststrm:ptCalendar:view', 'fa fa-calendar', 'admin', '2026-08-12 00:00:00', '', NULL, '按播出日期展示已订阅剧集的每集状态，含缺集/在途/已入库');
