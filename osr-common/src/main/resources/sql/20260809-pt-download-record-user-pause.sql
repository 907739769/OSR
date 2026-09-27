-- ----------------------------
-- 20260809: 下载记录的「用户暂停」（幂等脚本）
--
-- 下载记录页可以暂停 / 继续一个下载中的种子。暂停必须落库，因为下载追踪有两道按时间判的兜底：
-- 僵尸超时（默认推送后 24 小时仍未完成判失败）与元数据超时。暂停期间进度不涨，不扣掉暂停时长的话，
-- 用户暂停一天，种子就会被判成僵尸——而「没下完、没做过种」的种子还会被 OSR 从下载器里删掉。
--
-- user_paused_time    当前这次暂停开始的时间，NULL 表示没有处于用户暂停
-- user_paused_seconds 此前各次暂停累计的秒数，继续时把本次时长加进来；僵尸超时按「推送至今 − 累计暂停」算
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'user_paused_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `user_paused_time` DATETIME NULL DEFAULT NULL COMMENT ''用户暂停开始时间，NULL 表示未暂停'' AFTER `fail_ignored`', 'SELECT ''Column user_paused_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'user_paused_seconds');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `user_paused_seconds` BIGINT NOT NULL DEFAULT 0 COMMENT ''此前各次用户暂停累计秒数，僵尸超时扣除'' AFTER `user_paused_time`', 'SELECT ''Column user_paused_seconds already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
