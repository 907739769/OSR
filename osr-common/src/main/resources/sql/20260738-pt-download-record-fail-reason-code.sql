-- ----------------------------
-- 20260738: pt_download_record 增加 fail_reason_code 列，用于失败原因结构化分类展示（幂等脚本）
-- 只加列，不回填历史数据：历史 FAILED 记录该列为 NULL，前端按"未分类"处理。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'fail_reason_code');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `fail_reason_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''失败原因分类：TORRENT_NOT_FOUND/ZOMBIE_TIMEOUT/OTHER，历史失败记录为 NULL 表示未分类'' AFTER `fail_reason`', 'SELECT ''Column fail_reason_code already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
