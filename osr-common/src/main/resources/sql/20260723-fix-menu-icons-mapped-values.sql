-- ----------------------------
-- 20260723: 修复菜单图标不显示问题
-- 根因：前端 openlist-web/src/composables/useMenuIcon.ts 里维护了一份"Font Awesome类名
-- -> Element Plus图标组件"的映射表 iconMap，只有出现在这张表里的图标类名才会真正渲染出来，
-- 不在表里的类名（包括占位的'#'）都会被 v-if 隐藏，界面上什么都不显示。
-- 之前给 2060 挑的 'fa fa-sliders' 不在这张映射表里，所以不显示；2055 建单时用的占位 '#' 同理。
-- 改用映射表里已收录的类名：'fa fa-magic'（对应 MagicStick，呼应本功能里"测试"按钮同款图标）
-- 和 'fa fa-tasks'（对应 Tools，契合"一致性检查"的巡检语义）。
-- ----------------------------
UPDATE sys_menu SET icon = 'fa fa-magic' WHERE menu_id = 2060;
UPDATE sys_menu SET icon = 'fa fa-tasks' WHERE menu_id = 2055;
