-- ----------------------------
-- 20260812: 媒体服务器「入库后通知刷新」两列（幂等脚本）
--
-- 此前媒体服务器只被拿来查询（对账、观看状态、字幕流），OSR 写完 .strm / 重命名产物 / NFO 之后
-- 从不通知它，新文件要等媒体服务器自己的定时扫描才出现，「下载完成 → 能播」中间白等一个扫描周期，
-- 订阅的入库确认也跟着晚。
--
-- 1) library_notify：是否在新文件落盘后通知这台服务器按目录局部刷新，默认 '0'——
--    存量部署升级后行为不变，要用的到配置页打开。
-- 2) path_mapping：OSR 容器里的路径与媒体服务器看到的路径往往不同（/data/media → /media），
--    每行一条「OSR路径 => 媒体服务器路径」，按最长前缀匹配；空表示两边路径一致。
--    映射之后还要落在媒体服务器的某个媒体库目录下才会发通知，见 LibraryRefreshNotifier。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_media_server' AND COLUMN_NAME = 'library_notify');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_media_server` ADD COLUMN `library_notify` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT ''0'' COMMENT ''新文件落盘后通知刷新媒体库 0-否 1-是'' AFTER `enabled`', 'SELECT ''Column library_notify already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_media_server' AND COLUMN_NAME = 'path_mapping');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_media_server` ADD COLUMN `path_mapping` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT ''路径映射，每行「OSR路径 => 媒体服务器路径」，空表示两边一致'' AFTER `library_notify`', 'SELECT ''Column path_mapping already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
