-- ----------------------------
-- 20260805: 订阅记下媒体库里的观看状态（幂等脚本）
--
-- WatchStateSyncTask 每小时从媒体服务器读一次「这部剧看过哪几集、最近一次什么时候看的」：
-- 订阅卡片据此显示「已看 N 集」，自动补搜据此把最近在看的剧排在前面（单轮有预算，排不上的顺延）。
--
-- 两列都允许 NULL：NULL 表示「没有能读观看状态的媒体服务器」（Emby/Jellyfin 没配用户 ID、
-- 或一台都没配），与「读到了、一集都没看」（watched_count = 0）是两回事。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'watched_count');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `watched_count` INT DEFAULT NULL COMMENT ''媒体库里已看过的集数；NULL=读不到观看状态''', 'SELECT ''Column watched_count already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'last_watched_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `last_watched_time` DATETIME DEFAULT NULL COMMENT ''最近一次在媒体库里观看的时间'' AFTER `watched_count`', 'SELECT ''Column last_watched_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
