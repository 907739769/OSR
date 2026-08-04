-- ----------------------------
-- 20260758: pt_filter_config 新增 avoid_hit_and_run 列
--
-- 直接淘汰来自 H&R 考核站点的种子的硬开关。默认关闭——H&R 站点往往正是资源质量最好的站点，
-- 多数用户要的是"同等条件下优先用没有考核的"，那个诉求由 SortDimension.HR 降权维度满足；
-- 本开关是留给完全不愿承担保种义务的用户的。
--
-- 幂等 ADD，写法与 20260744-pt-filter-require-chinese-subtitle.sql 一致。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'avoid_hit_and_run');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `avoid_hit_and_run` CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''是否直接淘汰H&R考核站点的种子 0-否 1-是'' AFTER `require_chinese_subtitle`', 'SELECT ''Column avoid_hit_and_run already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
