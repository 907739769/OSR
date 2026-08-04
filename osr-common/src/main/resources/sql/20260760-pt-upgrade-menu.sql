-- ----------------------------
-- 20260760: 新增"PT洗版规则"页面菜单
--
-- 挂在 PT下载管理(2070) 分组下，排在"PT热门自动订阅"(2073, order_num=9)之后。
-- menu_id=2074：已核对 sql/ 目录下全部涉及 sys_menu 的脚本，2074 未被占用。
-- 图标 fa fa-arrow-circle-o-up 与同组内已用图标不重复。
-- 页面与后端接口同批次上线，直接 visible='0'(显示)，参照 20260741 的先例。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2074, 'PT洗版规则', 2070, 10, '/openlist/ptUpgradeConfig', '', 'C', '0', '1', 'openliststrm:ptUpgradeConfig:view', 'fa fa-arrow-circle-o-up', 'admin', '2026-08-04 00:00:00', '', NULL, 'PT 洗版(质量升级)规则：目标质量、比较维度顺序、并发与扫描周期');
