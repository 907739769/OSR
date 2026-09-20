-- ----------------------------
-- 20260796: 给 pt_download_record 补统计面板用得到的三条索引
--
-- 统计仪表盘的每个端点都是 `WHERE <时间列> >= ? GROUP BY ...`，而这张表此前只有
-- uk_indexer_guid / idx_sub_episode / idx_state / uk_tracking_tag / idx_downloader_state /
-- idx_hr_state —— 没有任何一条时间列索引。于是打开一次仪表盘就是四次全表扫描
-- （趋势三条线各一次 + Top 活跃订阅一次），首页的 PT 概览卡还会再来两次；
-- 而这张表只增不减，没有任何保留策略。
--
-- 三条分别对应：推送线(pushed_time)、完成线与平均耗时(completed_time)、
-- 失败线与失败原因分布(state='FAILED' AND update_time >= ?)。
-- 最后一条做成复合索引而不是单列 update_time：失败查询恒带 state 等值条件，
-- 复合索引能一次定位，单列索引则要先扫出全部近期更新的记录再回表筛状态。
-- ----------------------------

SET @idx := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND INDEX_NAME = 'idx_pushed_time');
SET @sql := IF(@idx = 0, 'ALTER TABLE `pt_download_record` ADD INDEX `idx_pushed_time`(`pushed_time`) USING BTREE', 'SELECT ''Index idx_pushed_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND INDEX_NAME = 'idx_completed_time');
SET @sql := IF(@idx = 0, 'ALTER TABLE `pt_download_record` ADD INDEX `idx_completed_time`(`completed_time`) USING BTREE', 'SELECT ''Index idx_completed_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND INDEX_NAME = 'idx_state_update_time');
SET @sql := IF(@idx = 0, 'ALTER TABLE `pt_download_record` ADD INDEX `idx_state_update_time`(`state`, `update_time`) USING BTREE', 'SELECT ''Index idx_state_update_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
