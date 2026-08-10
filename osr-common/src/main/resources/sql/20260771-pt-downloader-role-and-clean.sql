-- ----------------------------
-- 20260771: 下载器分工（订阅下载 / 仅做种）+ 自动删种规则（幂等脚本）
--
-- 背景一：SubscriptionEngine#resolveDownloader 在订阅没有显式指定下载器时，会从<b>所有</b>
-- enabled=1 的下载器里挑在途记录数最少的那个做负载均衡。用户新增第二个下载器（用途是接收
-- IYUU 转移+辅种的种子、只做种不下载）后，订阅会开始往它上面推种，这不是用户要的分工。
-- 新增 role 列把"参与订阅下载的池子"与"只做种的下载器"分开：只有 DOWNLOAD 进负载均衡池。
-- 存量行默认 DOWNLOAD，升级后行为不变。
--
-- 背景二：只做种的下载器需要按规则自动清理种子腾空间。规则由 pt_clean_rule 表承载，
-- 一个下载器可配多条「体积区间 → 最短做种时长」规则（如 >50GB 做满 3 天删、其余做满 7 天删），
-- 按 sort_order 从小到大取第一条体积区间命中的规则。下载器上只放总开关与全局护栏。
--
-- 「OSR 从不删种」这条原有约束在此开了第二个受控例外（第一个是 DownloadTrackService
-- #removeUselessTorrent）。护栏见 TorrentCleanService：H&R 未达标不删、辅种整组同删、
-- 每轮有上限、支持排除标签。
-- ----------------------------

-- pt_downloader.role
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'role');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `role` varchar(20) NOT NULL DEFAULT ''DOWNLOAD'' COMMENT ''下载器分工：DOWNLOAD-参与订阅下载 SEED_ONLY-仅做种（接收IYUU转移/辅种），不参与订阅下载'' AFTER `enabled`', 'SELECT ''Column role already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- pt_downloader.auto_delete_enabled
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'auto_delete_enabled');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `auto_delete_enabled` char(1) NOT NULL DEFAULT ''0'' COMMENT ''自动删种总开关 0-否 1-是；关闭时该下载器完全不参与清理'' AFTER `role`', 'SELECT ''Column auto_delete_enabled already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- pt_downloader.auto_delete_exclude_tags
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'auto_delete_exclude_tags');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `auto_delete_exclude_tags` varchar(255) NULL DEFAULT NULL COMMENT ''自动删种排除标签，逗号分隔；种子带其中任一标签则整组永不删除'' AFTER `auto_delete_enabled`', 'SELECT ''Column auto_delete_exclude_tags already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- pt_downloader.auto_delete_max_per_round
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'auto_delete_max_per_round');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `auto_delete_max_per_round` int NOT NULL DEFAULT 20 COMMENT ''单轮最多删除多少个辅种组，0表示不限；防止规则配错时一次清空整个保种盘'' AFTER `auto_delete_exclude_tags`', 'SELECT ''Column auto_delete_max_per_round already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- pt_clean_rule
CREATE TABLE IF NOT EXISTS `pt_clean_rule` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `downloader_id` int NOT NULL COMMENT '所属下载器ID',
  `name` varchar(64) NOT NULL COMMENT '规则名，仅用于展示',
  `min_size_gb` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '体积区间下界(GB，含)',
  `max_size_gb` decimal(10,2) NULL DEFAULT NULL COMMENT '体积区间上界(GB，不含)，NULL表示不限',
  `min_seed_hours` int NOT NULL DEFAULT 72 COMMENT '最短做种时长(小时)，种子累计做种达到该值才允许删除',
  `delete_files` char(1) NOT NULL DEFAULT '1' COMMENT '是否连同文件一起删除 0-否 1-是',
  `enabled` char(1) NOT NULL DEFAULT '1' COMMENT '是否启用 0-否 1-是',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '匹配顺序，值小的先匹配；第一条体积区间命中的规则生效',
  `remark` varchar(255) NULL DEFAULT NULL COMMENT '备注',
  `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_downloader_sort` (`downloader_id`, `sort_order`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'PT 下载器自动删种规则' ROW_FORMAT = Dynamic;
