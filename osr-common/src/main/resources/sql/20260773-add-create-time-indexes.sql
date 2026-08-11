-- ----------------------------
-- 20260773: 三张主业务表补 (create_time, 状态列) 复合索引（幂等脚本）
--
-- openlist_strm / openlist_copy / rename_detail 随媒体库规模线性增长且没有保留策略，
-- 而它们全部按 create_time 被查询，此前一条 create_time 索引都没有：
--   1) 首页统计卡片 OpenlistDashboardRestController#getStatsByStatus
--      SELECT 状态列, count(*) ... WHERE create_time BETWEEN ? AND ? GROUP BY 状态列
--   2) 首页趋势图 DashboardStatsService#trend
--      SELECT DATE_FORMAT(create_time,'%Y-%m-%d'), count(*), SUM(CASE WHEN 状态列=? ...)
--      WHERE create_time >= ? GROUP BY DATE_FORMAT(create_time,'%Y-%m-%d')
--   3) 三个列表页的时间范围筛选 + ORDER BY create_time DESC
--   4) IStrmService/ICopyService#retryAllFailed 的 WHERE 状态列=? ORDER BY create_time DESC LIMIT 200
-- 首页一打开就同时触发 1 和 2，即每次访问都对这三张最大的表各做一次全表扫描。
--
-- 列顺序是 (create_time, 状态列) 而不是反过来，两个理由：
--   * 上面四类查询都以 create_time 的范围或排序为主导，状态列只是聚合项或次要过滤项，
--     范围列放在前面才能让索引同时吃下 WHERE 范围与 ORDER BY；
--   * 状态列（varchar(2)）跟在后面几乎不占空间，却让 1 和 2 变成覆盖索引扫描 ——
--     count/GROUP BY/SUM(CASE) 全部在索引里算完，不必回聚簇索引取行。
-- 第 4 类查询的理想索引是反向的 (状态列, create_time)，但它是用户手动触发的低频操作，
-- 走本索引倒序扫描同样能在命中 200 条后停下，不值得为它再加一条索引拖慢批量写入。
-- ----------------------------

-- openlist_strm: 首页统计/趋势/列表页时间筛选
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_strm' AND INDEX_NAME = 'idx_strm_create_time_status');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_strm` ADD INDEX `idx_strm_create_time_status`(`create_time`, `strm_status`)', 'SELECT ''Index idx_strm_create_time_status already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- openlist_copy: 同上
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_copy' AND INDEX_NAME = 'idx_copy_create_time_status');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_copy` ADD INDEX `idx_copy_create_time_status`(`create_time`, `copy_status`)', 'SELECT ''Index idx_copy_create_time_status already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- rename_detail: 同上（状态列就叫 status）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rename_detail' AND INDEX_NAME = 'idx_rename_create_time_status');
SET @sql := IF(@exist = 0, 'ALTER TABLE `rename_detail` ADD INDEX `idx_rename_create_time_status`(`create_time`, `status`)', 'SELECT ''Index idx_rename_create_time_status already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
