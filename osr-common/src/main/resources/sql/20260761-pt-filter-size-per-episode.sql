-- ----------------------------
-- 20260761: 体积阈值按「每集」判定
--
-- 背景：min_size / max_size / preferred_size 一直是拿整个种子的体积去比的，而剧集的种子
-- 常常是区间包（S01E01-E06）或季包，整包体积是单集的几倍到几十倍。同一份阈值对单集和多集包
-- 不可能同时成立：按单集设的上限会把所有多集包一刀切光，按季包设的下限会放行所有单集垃圾资源，
-- SIZE 排序维度在单集与包混排的候选里也只是噪声（季包必然被判成离偏好体积极远）。
--
-- 开启后三项阈值统一折算到每集再比较。单集资源折算前后取值完全相同，因此这个开关只影响多集包，
-- 默认给 '1'（开启）：对既有用户来说，唯一的行为变化正好发生在原本就判错的那批候选上。
-- ----------------------------
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'size_per_episode');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `size_per_episode` CHAR(1) NOT NULL DEFAULT ''1'' COMMENT ''体积上下限与偏好体积是否按每集判定 0-否 1-是。剧集种子常是区间包/季包，整包体积是单集的数倍，不折算则同一份阈值对单集与包不可能同时成立'' AFTER `preferred_size`', 'SELECT ''Column size_per_episode already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
