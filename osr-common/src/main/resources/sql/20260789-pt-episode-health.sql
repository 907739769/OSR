-- ----------------------------
-- 20260789: 缺集体检（逾期未入库提醒）
--
-- 背景：自动补搜(auto_search)的库默认值是 '0'，建订阅时也不会自动打开——那是刻意的，
-- 每条开着的订阅每轮都要向每个索引器打满一整份检索计划，全量开启会让追完的老剧空转。
-- 代价是「订阅建完就没再管过、集一直缺着」这个最常见的场景在改造前一条提醒都没有：
-- 补搜落空通知只对开着开关的订阅发，StuckEpisodeSweepService 管的是「下完了没入库」，
-- 追剧日历只按日期铺格子、不回答"这一格为什么还是灰的"。
--
-- 本次不改 auto_search 的默认值（那会让存量老剧集体空转），而是新增一个「缺集体检」
-- 页面把该开而没开的订阅直接列出来、支持一键开启，再配一条定期提醒。
--
-- 体检本身是纯查询，复用已有字段（air_date / state / file_confirmed /
-- last_auto_search_no_result / last_auto_search_reject_sign），不需要新表。
-- 这里只加通知去重要用的两列。
-- ----------------------------

-- 1. 逾期缺集通知的去重指纹。
--    逾期缺集与补搜落空不同：它天天都在，不去重的话每轮都会重发同一条。
--    指纹变了（新缺一集/补上一集）立刻再提醒，没变则按周重提醒——只按"发过就不再发"
--    处理的话，一部永远补不上的剧提醒一次之后就再无声息，而它最该被记住。
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'pt_subscription'
                 AND COLUMN_NAME = 'last_overdue_notify_sign');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_subscription` ADD COLUMN `last_overdue_notify_sign` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''上次逾期缺集通知的指纹(集数:排序去重的集号)，NULL=当前无逾期缺集'' AFTER `last_auto_search_reject_sign`',
    'SELECT ''Column last_overdue_notify_sign already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'pt_subscription'
                 AND COLUMN_NAME = 'last_overdue_notify_time');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_subscription` ADD COLUMN `last_overdue_notify_time` datetime(0) NULL DEFAULT NULL COMMENT ''上次发出逾期缺集通知的时间，配合指纹做周期性重提醒'' AFTER `last_overdue_notify_sign`',
    'SELECT ''Column last_overdue_notify_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 体检查询按 (state, air_date) 筛，且只看未入库的那三种状态。
--    已有的 idx_episode_air_date 单列索引对这个查询帮助有限：绝大多数行是 IN_LIBRARY，
--    先按日期过滤会扫回一大批随后被状态条件丢掉的行。
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'pt_subscription_episode'
                 AND INDEX_NAME = 'idx_episode_state_air_date');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_subscription_episode` ADD INDEX `idx_episode_state_air_date` (`state`, `air_date`)',
    'SELECT ''Index idx_episode_state_air_date already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 「缺集体检」菜单，挂在 PT 追剧(2070) 下，排在追剧日历之后。
--    归到「追剧」而不是「下载」：用户来这里问的是"我的剧齐了吗"，不是"这个种子下得怎么样"。
--    menu_id=2082：已核对 sql/ 目录下全部涉及 sys_menu 的脚本，当前最大为 2081。
--    图标直接写 mdi 名（见 20260780），不要再写 fa fa-*，也不需要去 useMenuIcon.ts 登记。
--    分组 2070 本来就有授权行，挂在它下面的子菜单不需要额外继承 sys_role_menu。
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2082, '缺集体检', 2070, 2, '/openlist/ptHealth', '', 'C', '0', '1', 'openliststrm:ptHealth:view', 'mdi-stethoscope', 'admin', '2026-08-17 00:00:00', '', NULL, '列出播出多日仍未入库的集，给出「为什么还缺」的诊断与处置入口');

-- 追剧日历之后的两项顺延，保持组内顺序稳定
UPDATE `sys_menu` SET `order_num` = 3 WHERE `menu_id` = 2064;
UPDATE `sys_menu` SET `order_num` = 4 WHERE `menu_id` = 2073;
