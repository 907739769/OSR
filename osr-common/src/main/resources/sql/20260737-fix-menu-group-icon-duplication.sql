-- ----------------------------
-- 20260737: 修复菜单分组图标与自己的子菜单图标重复的问题
-- 20260736 建分组时挑图标（fa-copy/fa-video-play/fa-edit）漏看了更早的
-- 20260428-menu-icons.sql，那个文件已经给部分子菜单（2013/2019/2025/2037/2049）
-- 设置了完全相同的图标，导致侧边栏里分组和子菜单图标一模一样。
-- 三个新图标类名（send-o/video-camera/file-code-o）均已存在于前端
-- openlist-web/src/composables/useMenuIcon.ts 的 iconMap 中，且和这14个
-- 子菜单当前使用的所有图标（copy/video-play/list/edit/tasks/magic/rss/
-- download/server/bookmark-o/sliders/list-ul）都不重复。
-- ----------------------------
UPDATE `sys_menu` SET `icon` = 'fa fa-send-o' WHERE `menu_id` = 2067;
UPDATE `sys_menu` SET `icon` = 'fa fa-video-camera' WHERE `menu_id` = 2068;
UPDATE `sys_menu` SET `icon` = 'fa fa-file-code-o' WHERE `menu_id` = 2069;
