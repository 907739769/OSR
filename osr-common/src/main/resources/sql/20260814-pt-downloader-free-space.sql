-- ----------------------------
-- 20260814: 下载器剩余空间告警与按空间删种（幂等脚本）
--
-- 1) free_space_warn_gb：剩余空间低于它时告警（首页待办 + 通知）。NULL 表示不告警，存量部署升级后行为不变。
--    磁盘写满时下载器的表现是「任务卡住 / 报错」，OSR 这边看到的只是下载失败，原因要用户自己去下载器里翻。
-- 2) auto_delete_free_below_gb：开了自动删种时，只在剩余空间低于它才删，且删到腾够空间为止。
--    NULL 表示不看空间、照旧按规则删（引入前的行为）。读不到剩余空间时一个都不删——判据缺失不动手。
-- 剩余空间本身不落库：它每 15 分钟读一次、只在进程内保留（DownloaderSpaceRegistry），与下载器离线状态同一取向。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'free_space_warn_gb');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `free_space_warn_gb` decimal(10,2) NULL DEFAULT NULL COMMENT ''剩余空间告警线（GB），NULL 不告警'' AFTER `auto_delete_max_per_round`', 'SELECT ''Column free_space_warn_gb already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'auto_delete_free_below_gb');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `auto_delete_free_below_gb` decimal(10,2) NULL DEFAULT NULL COMMENT ''只在剩余空间低于此值（GB）时自动删种，删够即停；NULL 不看空间'' AFTER `free_space_warn_gb`', 'SELECT ''Column auto_delete_free_below_gb already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
