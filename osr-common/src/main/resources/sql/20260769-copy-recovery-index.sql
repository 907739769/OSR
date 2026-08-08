-- ----------------------------
-- 20260769: 复制任务兜底恢复扫描所需索引（幂等脚本）
--
-- CopyRecoveryTask 每 5 分钟按 copy_status 捞一次待裁决记录，
-- STRM 补生成还要叠加 update_time 范围条件。openlist_copy 会随同步量线性增长，
-- 没有这个索引时两条查询都是全表扫描。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_copy' AND INDEX_NAME = 'idx_copy_status_update');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_copy` ADD INDEX `idx_copy_status_update`(`copy_status`, `update_time`)', 'SELECT ''Index idx_copy_status_update already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
