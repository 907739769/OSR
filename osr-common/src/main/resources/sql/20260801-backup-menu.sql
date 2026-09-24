-- ----------------------------
-- 20260801: 配置备份与恢复
--
-- 只加一个菜单，不建表：备份文件由用户自己下载保存，服务端不留副本——
-- 留在服务端的备份在「整台机器坏了」这个最需要它的场景里恰恰跟着一起没了。
--
-- menu_id=2084：已核对 sql/ 目录下全部涉及 sys_menu 的脚本，当前最大为 2083（MCP 令牌）。
-- 挂在「系统管理」(menu_id=1) 下，排在 MCP 令牌(2083, order_num=8)之后。
--
-- 图标 database-backup 已在 osr-web/src/plugins/lucideIcons.ts 的 icons 表里登记，
-- 漏登记的话菜单上显示一个问号。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2084, '备份与恢复', 1, 9, '/system/backup', '', 'C', '0', '1', 'system:backup:view', 'database-backup', 'admin', '2026-09-24 00:00:00', '', NULL, '导出/恢复各项配置与订阅');

-- 角色授权按「参数设置」继承一份。理由同 20260794：非管理员走 selectMenusByUserId，
-- 每个菜单项都要有自己的授权行。页面的接口本身仍只对管理员开放（BackupRestController），
-- 这里授权只决定菜单是否可见。
INSERT IGNORE INTO `sys_role_menu`(`role_id`, `menu_id`)
SELECT `role_id`, 2084 FROM `sys_role_menu` WHERE `menu_id` = 106;
