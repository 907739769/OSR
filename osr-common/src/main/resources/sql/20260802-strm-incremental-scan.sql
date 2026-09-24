-- ----------------------------
-- 20260802: STRM 增量扫描（幂等脚本）
--
-- 定时 STRM 任务原先每次都把整棵目录树逐个 fs/list 一遍。增量扫描跳过「修改时间没变的叶子目录」：
-- 一个目录的修改时间在列它的<父目录>时就能拿到，没变且上次已全部处理成功的叶子目录（只有文件、
-- 没有子目录，如 Season 1、电影名 (年份)）这次就不必再列。
--
-- 只跳叶子、不跳子树：目录的修改时间只随它<直接>包含的条目增减而变，不会向上传递——
-- 新一集放进 /电视剧/三体/Season 2/，变的只有 Season 2，「三体」和「电视剧」都不变。
-- 按子树跳过的话，最常见的「已有季里新增一集」恰恰会被漏掉。
--
-- 前提是网盘驱动在目录里增删文件时会更新该目录的修改时间。本地存储一定会，各家网盘不一定，
-- 所以按任务开启、默认关闭，并定期全量兜底（openlist.strm.fullscan.days）。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_strm_task' AND COLUMN_NAME = 'incremental');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_strm_task` ADD COLUMN `incremental` CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''定时执行时是否增量扫描 0-否 1-是'' AFTER `strm_override`', 'SELECT ''Column incremental already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_strm_task' AND COLUMN_NAME = 'last_full_scan_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_strm_task` ADD COLUMN `last_full_scan_time` DATETIME DEFAULT NULL COMMENT ''上次全量扫描完成时间；NULL=还没全量扫过，下次必定全量'' AFTER `incremental`', 'SELECT ''Column last_full_scan_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 叶子目录快照。只记「上次列过、没有子目录、里面的文件全部处理完」的目录，
-- 有一行才可能被跳过；没有行（或 modified / settings_sign 对不上）就照常列。
-- 路径可能很长，唯一索引建在它的 SHA-256 上（utf8mb4 下 varchar 索引上限只有 768 字符）。
CREATE TABLE IF NOT EXISTS `openlist_strm_dir_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `path_hash` char(64) NOT NULL COMMENT 'SHA-256(dir_path) 十六进制小写，唯一键',
  `dir_path` varchar(2000) NOT NULL COMMENT '网盘目录路径（不带末尾斜杠）',
  `modified` varchar(64) NOT NULL COMMENT '列父目录时拿到的该目录修改时间，原样保存、按字符串比对',
  `settings_sign` char(64) NOT NULL COMMENT '当时生效的 STRM 设置指纹；设置改过（扩展名、最小体积、是否下字幕等）就不能跳过',
  `scanned_time` datetime NOT NULL COMMENT '最后一次确认该快照的时间，全量扫描后据此清掉已不存在的目录',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_strm_dir_snapshot_hash` (`path_hash`) USING BTREE,
  INDEX `idx_strm_dir_snapshot_path` (`dir_path`(255)) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'STRM 增量扫描的叶子目录快照' ROW_FORMAT = Dynamic;

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'STRM 增量扫描的全量间隔（天）', 'openlist.strm.fullscan.days', '7', 'N', 'admin', '2026-09-24 00:00:00',
       '开启了增量扫描的 STRM 任务，距上次全量超过这么多天时，定时执行改为全量扫描一次，兜住网盘驱动不更新目录修改时间的情况。填 0 表示每次都全量'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.strm.fullscan.days');
