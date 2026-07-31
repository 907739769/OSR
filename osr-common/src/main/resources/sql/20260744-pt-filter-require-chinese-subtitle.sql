-- ----------------------------
-- 20260744: pt_filter_config 新增 require_chinese_subtitle 列
-- 外语电影是否需要中文字幕的全局开关。订阅级 filter_override 可以独立覆盖。
-- 幂等 ADD：列存在则跳过，不存在则新增。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'require_chinese_subtitle');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `require_chinese_subtitle` CHAR(1) DEFAULT ''0'' COMMENT ''外语电影是否需要中字 0-否 1-是'' AFTER `preferred_size`', 'SELECT ''Column require_chinese_subtitle already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
