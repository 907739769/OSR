-- 1. 插入菜单
INSERT INTO sys_menu (menu_name, parent_id, order_num, url, menu_type, visible, perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES ('实时日志', '2', '10', '/monitor/log', 'C', '0', 'monitor:log:view', 'fa fa-file-code-o', 'admin', NOW(), '', NULL, '实时日志监控菜单');
