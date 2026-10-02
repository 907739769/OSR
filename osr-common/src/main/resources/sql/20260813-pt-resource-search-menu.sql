-- ----------------------------
-- 20260813: 资源搜索页菜单
--
-- 不建订阅、直接按关键词搜全部站点，看站上有什么，再决定直接下载还是转为订阅。
-- 只加一个菜单，不建表：搜索结果不落库；直接下载不建下载记录（理由见 ResourceSearchService 类注释）。
--
-- menu_id=2085：已核对 sql/ 目录下全部涉及 sys_menu 的脚本，当前最大为 2084（备份与恢复）。
-- 挂在「PT 追剧」(2070) 下、排在「订阅管理」(2064, order_num=3) 之后，「热门自动订阅」(2073) 顺延到 5。
--
-- 图标 search 已在 osr-web/src/plugins/lucideIcons.ts 的 icons 表里登记。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2085, '资源搜索', 2070, 4, '/openlist/ptSearch', '', 'C', '0', '1', 'openliststrm:ptSearch:view', 'search', 'admin', '2026-10-01 00:00:00', '', NULL, '不建订阅，按关键词直接搜全部站点，可直接下载或转为订阅');

UPDATE `sys_menu` SET `order_num` = 5 WHERE `menu_id` = 2073 AND `order_num` = 4;

-- 角色授权按「订阅管理」继承一份：非管理员走 selectMenusByUserId，每个菜单项都要有自己的授权行。
-- 搜索对能看订阅的人开放；直接下载在接口侧另限管理员（PtResourceSearchRestController）。
INSERT IGNORE INTO `sys_role_menu`(`role_id`, `menu_id`)
SELECT `role_id`, 2085 FROM `sys_role_menu` WHERE `menu_id` = 2064;
