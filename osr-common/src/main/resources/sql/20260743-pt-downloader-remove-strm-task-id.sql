-- ----------------------------
-- 20260743: pt_downloader 移除 strm_task_id 列
-- 下载完成后联动触发 STRM 增量生成的功能已移除，下载器不再需要关联 STRM 任务。
-- 幂等 DROP：列存在则删除，不存在则跳过。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'strm_task_id');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pt_downloader` DROP COLUMN `strm_task_id`', 'SELECT ''Column strm_task_id already removed''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
