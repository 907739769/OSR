-- ----------------------------
-- 20260798: sys_job_log 补 start_time / end_time 两列（幂等脚本）
--
-- 实体 SysJobLog 一直有 startTime/endTime 字段，前端「执行记录」也一直在用它们渲染
-- 「开始时间」「耗时」两列，但建表语句里从来没有这两列，insert 也没写过——于是这两列
-- 在界面上永远是 '-'，真实耗时只存在于 job_message 的那句「总共耗时：xx毫秒」里。
-- 存量记录补不回来，保持 NULL，由前端回落到 create_time 展示。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_job_log' AND COLUMN_NAME = 'start_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `sys_job_log` ADD COLUMN `start_time` datetime(0) NULL DEFAULT NULL COMMENT ''任务开始时间'' AFTER `exception_info`', 'SELECT ''Column start_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_job_log' AND COLUMN_NAME = 'end_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `sys_job_log` ADD COLUMN `end_time` datetime(0) NULL DEFAULT NULL COMMENT ''任务结束时间'' AFTER `start_time`', 'SELECT ''Column end_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 「上次执行」要按 job_name + job_group 回查最近一条日志（sys_job_log 没有 job_id 列），
-- 没有索引时这张只增不减的表会随规模退化成全表扫
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_job_log' AND INDEX_NAME = 'idx_job_log_name_group_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `sys_job_log` ADD INDEX `idx_job_log_name_group_time` (`job_name`, `job_group`, `create_time`)', 'SELECT ''Index idx_job_log_name_group_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
