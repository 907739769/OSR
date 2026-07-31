-- ----------------------------
-- 20260736: OpenListStrm 菜单分类 —— 按功能域(同步/STRM/重命名/PT下载)分组
-- 现状：OpenListStrm(2006) 下平铺挂着 14 个二级菜单，随着功能增多难以查找。
-- 本脚本新增 4 个 M 类型子目录，把这 14 个菜单通过 UPDATE parent_id/order_num
-- 挪到对应子目录下，menu_id 不变，不影响现有角色的菜单授权（sys_role_menu 按
-- menu_id 关联，与 parent_id 无关）。
--
-- 幂等性：新增用 INSERT IGNORE + 显式主键；UPDATE 语句本身天然幂等（重复执行
-- 结果不变），配合 SimpleDdl「整文件成功才记入 ddl_history」的机制不会有部分
-- 执行风险。
-- 图标类名均已在 openlist-web/src/composables/useMenuIcon.ts 的 iconMap 中，
-- 避免重蹈历史上"图标不显示"的坑（见 commit 0248e124）。
-- ----------------------------

-- 新增 4 个子目录菜单
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2067, '同步管理', 2006, 1, '#', '', 'M', '0', '1', NULL, 'fa fa-copy', 'admin', '2026-07-24 00:00:00', '', NULL, '同步任务相关菜单分组'),
(2068, 'STRM管理', 2006, 2, '#', '', 'M', '0', '1', NULL, 'fa fa-video-play', 'admin', '2026-07-24 00:00:00', '', NULL, 'STRM生成相关菜单分组'),
(2069, '重命名管理', 2006, 3, '#', '', 'M', '0', '1', NULL, 'fa fa-edit', 'admin', '2026-07-24 00:00:00', '', NULL, '重命名相关菜单分组'),
(2070, 'PT下载管理', 2006, 4, '#', '', 'M', '0', '1', NULL, 'fa fa-bars', 'admin', '2026-07-24 00:00:00', '', NULL, 'PT下载相关菜单分组');

-- 同步管理(2067)：同步任务配置、同步任务记录
UPDATE `sys_menu` SET `parent_id` = 2067, `order_num` = 1 WHERE `menu_id` = 2025;
UPDATE `sys_menu` SET `parent_id` = 2067, `order_num` = 2 WHERE `menu_id` = 2013;

-- STRM管理(2068)：strm任务配置、STRM生成记录
UPDATE `sys_menu` SET `parent_id` = 2068, `order_num` = 1 WHERE `menu_id` = 2037;
UPDATE `sys_menu` SET `parent_id` = 2068, `order_num` = 2 WHERE `menu_id` = 2019;

-- 重命名管理(2069)：重命名任务配置、重命名规则设置、重命名明细、重命名一致性检查
UPDATE `sys_menu` SET `parent_id` = 2069, `order_num` = 1 WHERE `menu_id` = 2049;
UPDATE `sys_menu` SET `parent_id` = 2069, `order_num` = 2 WHERE `menu_id` = 2060;
UPDATE `sys_menu` SET `parent_id` = 2069, `order_num` = 3 WHERE `menu_id` = 2043;
UPDATE `sys_menu` SET `parent_id` = 2069, `order_num` = 4 WHERE `menu_id` = 2055;

-- PT下载管理(2070)：PT索引器、PT下载器、媒体服务器、PT订阅、PT过滤规则、PT下载记录
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 1 WHERE `menu_id` = 2061;
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 2 WHERE `menu_id` = 2062;
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 3 WHERE `menu_id` = 2063;
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 4 WHERE `menu_id` = 2064;
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 5 WHERE `menu_id` = 2065;
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 6 WHERE `menu_id` = 2066;
