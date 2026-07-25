-- ----------------------------
-- 20260741: 新增"PT统计仪表盘"页面菜单
-- 挂在 PT下载管理(2070) 分组下，排在"PT下载记录"(2066, order_num=6)之后。
-- 页面与后端接口在同一批次上线，直接 visible='0'(显示)，参照 20260731 的先例。
-- 图标 fa fa-bar-chart 映射到前端 TrendCharts 组件的工作由后续"PT订阅统计仪表盘"计划任务10完成，
-- 本文件仅负责菜单数据迁移。图标选择不与同组内其余6个已用图标(rss/download/server/bookmark-o/sliders/list-ul)
-- 及父分组自己的 fa-bars 重复。
-- 注：任务简报原计划编号 20260738 与 menu_id=2071 需在实现前核实，因与本计划并行的其他计划
-- 已抢占 20260738(pt-download-record-fail-reason-code)/20260739(pt-subscription-download-override)/
-- 20260740(notify-webhook-config)。经核对 sql/ 目录下所有涉及 sys_menu 的脚本，menu_id=2071
-- 未被占用，故沿用；文件编号改为当前实际可用的下一个值 20260741。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2071, 'PT统计仪表盘', 2070, 7, '/openlist/ptStatsDashboard', '', 'C', '0', '1', 'openliststrm:ptStatsDashboard:view', 'fa fa-bar-chart', 'admin', '2026-07-24 00:00:00', '', NULL, 'PT 订阅下载统计仪表盘：下载量趋势/索引器命中率/失败原因分布/Top活跃订阅');
