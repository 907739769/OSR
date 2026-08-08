-- ----------------------------
-- 20260767: 重命名一致性检查补上「反向」方向（文件 -> 记录）
--
-- 背景：原扫描只有一个方向——从 rename_detail 出发，查它指向的文件还在不在。
-- 这个方向结构上发现不了「文件还在、记录没了」的残骸，而那恰恰是最常见的两种：
--   1) 用户在明细页点了「删除记录」——只删数据库行，磁盘上的 STRM/视频/NFO/图片全留着
--   2) 识别错了改标题重试——旧实现只删旧主文件，旧目录里的单集 NFO、season.nfo、
--      tvshow.nfo 和七张剧集图原样留下，Emby/Jellyfin 会扫出一个只有元数据没有视频的鬼剧集
-- 两种残骸在库里都没有任何记录指着，正向扫描根本走不到那些路径。
--
-- 本次改动让 rename_orphan 同时承载两类发现，因此 detail_id 必须允许为空
-- （反向发现没有 detail 可挂靠），去重改由 (new_path, new_name) 承担。
-- MySQL 的 UNIQUE 索引允许多行 NULL，故 uk_detail_id 保持原样即可继续为正向发现去重。
--
-- reason 新增三个取值（见 OrphanReason）：
--   local_extra   目标库里的媒体文件在 rename_detail 里查不到记录
--   metadata_only 目录里只剩 NFO/图片/字幕，没有任何媒体文件
--   empty_dir     完全空目录
-- ----------------------------

-- detail_id 允许为空：反向发现挂不到任何 rename_detail 行
ALTER TABLE `rename_orphan` MODIFY COLUMN `detail_id` int(10) UNSIGNED NULL DEFAULT NULL COMMENT '关联rename_detail.id；反向发现（无主文件/残留目录）为空';

-- title 原为 varchar(32)，正向发现存的是刮削出来的剧名，反向发现存的是文件名/目录名，
-- 后者动辄上百字符。存不下会直接报错中断整轮扫描，不是截断
ALTER TABLE `rename_orphan` MODIFY COLUMN `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '标题；反向发现存文件名或目录名';

ALTER TABLE `rename_orphan` MODIFY COLUMN `reason` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '孤儿原因 local_missing-本地产物丢失 source_missing-网盘源已删 local_extra-无主媒体文件 metadata_only-仅剩元数据的目录 empty_dir-空目录';

-- 反向发现每轮扫描都要按路径回查「上一轮记过没有」，没有索引会退化成全表扫
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rename_orphan' AND INDEX_NAME = 'idx_path_name');
SET @sql := IF(@idx = 0, 'ALTER TABLE `rename_orphan` ADD INDEX `idx_path_name`(`new_path`(255), `new_name`(191)) USING BTREE', 'SELECT ''Index idx_path_name already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
