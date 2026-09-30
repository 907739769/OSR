-- ----------------------------
-- 20260811: 订阅级季包策略 + 缺集体检自动开启补搜（幂等脚本）
--
-- 1) pt_subscription.season_pack_policy：补搜时季包与单集谁先上。
--    AUTO            = 按 pt.search.season-pack-min-missing 判（本轮缺得多先试季包，缺得少先试单集）
--    EPISODE         = 用户手动设为单集优先，季包只作兜底
--    EPISODE_LEARNED = 这条订阅推过的季包被下载器文件列表证实不含（或不全含）目标集，系统自动转为单集优先
--    背景：动漫的「季包」常常只是半季（标题写 S01、实际只有 1-12 集），缺 13 集往后时季包优先会
--    「推包 → 包里没有 → 退回缺失 → 下一轮换个字幕组的包再推」，单集永远轮不到。
--    自动标记不会自己清掉，用户在订阅卡片上改回「季包优先」时才清。
--
-- 2) pt_subscription.health_auto_search_time：自动补搜是被缺集体检自动打开的时刻；NULL 表示
--    自动补搜不是体检打开的（用户手动开的、或压根没开）。体检只会关掉它自己打开的那些，
--    用户手动操作过开关后这一列即被清空，归用户所有。
--
-- 3) sys_config openlist.pt.health.autosearch：体检发现逾期缺集时自动开启补搜，默认关。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'season_pack_policy');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `season_pack_policy` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT ''AUTO'' COMMENT ''补搜季包策略 AUTO/EPISODE/EPISODE_LEARNED'' AFTER `auto_search`', 'SELECT ''Column season_pack_policy already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'health_auto_search_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `health_auto_search_time` datetime(0) NULL DEFAULT NULL COMMENT ''自动补搜被缺集体检自动开启的时刻，NULL=非体检开启'' AFTER `season_pack_policy`', 'SELECT ''Column health_auto_search_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '缺集体检自动开启补搜', 'openlist.pt.health.autosearch', '0', 'N', 'admin', '2026-09-30 00:00:00',
       '每天体检时，对播出多日仍缺集、但没开自动补搜的订阅自动打开补搜；补齐后（订阅完结，或 7 天内既无缺集也无新下载）再自动关掉。只关它自己打开的，手动开的不动'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.pt.health.autosearch');
