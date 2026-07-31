-- ----------------------------
-- 20260731: 新增"PT下载记录"页面菜单
-- 页面与后端接口在同一批次上线，直接 visible='0'(显示)，无需像
-- 20260725/20260727 那样分两步（先隐藏后翻显示）。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2066, 'PT下载记录', 2006, 15, '/openlist/ptDownloadRecord', '', 'C', '0', '1', 'openliststrm:ptDownloadRecord:view', 'fa fa-list-ul', 'admin', '2026-07-31 00:00:00', '', NULL, 'PT 下载记录查看与失败重试');
