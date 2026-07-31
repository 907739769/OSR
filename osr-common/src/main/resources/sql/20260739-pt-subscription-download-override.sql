-- ----------------------------
-- 20260739: pt_subscription 增加 download_override 列，用于订阅级下载追踪参数覆盖（幂等脚本）
-- 当前仅支持 zombieTimeoutHours 键，空表示全用全局配置，语义与 filter_override 一致但字段独立。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'download_override');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `download_override` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''订阅级下载追踪覆盖(JSON)，当前仅支持 zombieTimeoutHours 键，空表示全用全局配置'' AFTER `filter_override`', 'SELECT ''Column download_override already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
