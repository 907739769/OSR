-- ----------------------------
-- 20260735: pt_downloader 增加 strm_task_id 列，用于下载完成后联动触发对应 STRM 任务路径的增量生成（幂等脚本）
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'strm_task_id');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `strm_task_id` int NULL DEFAULT NULL COMMENT ''关联的STRM任务ID(openlist_strm_task.strm_task_id)，下载完成后据此触发一次增量STRM生成并提前对账订阅，为空则不触发，靠LibrarySyncTask兜底'' AFTER `save_path`', 'SELECT ''Column strm_task_id already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
