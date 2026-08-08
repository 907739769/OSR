-- ----------------------------
-- 20260768: 集级「文件已确认下好」标记，避免上传未完成的集被当成卡死重下
--
-- 背景：StuckEpisodeSweepService 把「关联下载记录已 COMPLETED 超过 N 小时、集仍是 IN_FLIGHT」
-- 一律判成卡死并退回 MISSING。这个判据分不开两种完全不同的情形：
--
--   A. 这一集的文件根本不在种子里（季包多占）        → 该退回重搜
--   B. 文件下好了，只是还没传上网盘 / 还没入库        → 绝不该重下
--
-- B 在慢速上传场景下是常态：网盘秒传要等别人先传过同一份文件，否则只能真传，
-- 大文件传几个小时甚至跨天、中途失败重来都很正常（失败会落进 openlist_copy_record，
-- 可在复制记录页重试）。此时本地文件明明已经下好并在做种，重下一遍纯属浪费带宽和
-- H&R 保种义务，而且每轮还会累加 fail_count，三次之后把一个好端端的集熔断成 BLOCKED。
--
-- DownloadTrackService 读下载器真实文件列表时，本就算出了「这个种子里到底有哪些集」
-- （reconcileClaims 用它找多占的集）。把这个结论落到集上，清扫就能区分 A 和 B：
-- file_confirmed=1 的集只告警、永不退回；=0 的维持原有清扫行为。
--
-- 默认 '0'（未确认）：存量数据一律走原有行为，不因为这次升级改变任何既有集的命运。
-- ----------------------------
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription_episode' AND COLUMN_NAME = 'file_confirmed');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription_episode` ADD COLUMN `file_confirmed` CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''该集的文件是否已在下载器的真实文件列表里确认存在 0-否 1-是。为 1 时卡死清扫只告警不退回——文件已下好，没入库是上传/STRM/刮削链路的事，重下解决不了'' AFTER `download_id`', 'SELECT ''Column file_confirmed already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
